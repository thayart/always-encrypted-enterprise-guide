# 7. การบริหารจัดการคีย์ (Key Management)

การจัดการคีย์คือหัวใจของความปลอดภัยและความสำเร็จของ Always Encrypted ในระดับองค์กร เพราะหากคีย์หาย ข้อมูลจะกู้คืนไม่ได้เลย และหากคีย์รั่วไหล ข้อมูลทั้งหมดจะไม่ปลอดภัยอีกต่อไป

## 7.1 หลักการออกแบบ (Design Principles)

1. **แยกหน้าที่ (Separation of Duties)**: ผู้ดูแล key store (เช่น ทีม Security/IAM) ควรเป็นคนละกลุ่มกับ DBA ที่ดูแลฐานข้อมูล
2. **Least Privilege**: จำกัดสิทธิ์ "unwrap/decrypt" บน CMK ให้เฉพาะ service account ของแอปพลิเคชันที่จำเป็นต้องถอดรหัสจริง ๆ
3. **High Availability**: Key store ต้องมี availability สูงอย่างน้อยเท่ากับฐานข้อมูล เพราะถ้า key store ล่ม แอปพลิเคชันจะ query ข้อมูลเข้ารหัสไม่ได้เลยแม้ฐานข้อมูลจะทำงานปกติ
4. **Disaster Recovery**: ต้องมีแผนสำรอง (escrow) คีย์ไว้ในที่ปลอดภัยแยกต่างหาก เพื่อกู้คืนกรณี key store หลักเสียหาย
5. **Audit Trail**: ทุกการเข้าถึงคีย์ (โดยเฉพาะ unwrap/decrypt) ควรถูกบันทึก log เพื่อตรวจสอบย้อนหลังได้

## 7.2 ตัวเลือก Key Store สำหรับองค์กร

### 7.2.1 Azure Key Vault (แนะนำสำหรับ Production ส่วนใหญ่)

**ข้อดี**:
- รองรับ RBAC และ Access Policy แบบละเอียด (แยกสิทธิ์ get/list/create/unwrapKey/wrapKey)
- มี audit log ผ่าน Azure Monitor / Diagnostic Settings ครบถ้วน
- รองรับ Managed HSM tier สำหรับ FIPS 140-2 Level 3
- รองรับ soft-delete และ purge protection ป้องกันการลบคีย์โดยไม่ตั้งใจ
- รองรับการหมุนเวียนคีย์ผ่าน versioning โดยไม่ต้องเปลี่ยน key path หลัก

**การตั้งค่าสิทธิ์ที่แนะนำ**:

| บทบาท | สิทธิ์ที่ควรได้รับ |
|---|---|
| Security/PKI Team (ผู้ดูแล Key Vault) | Key Vault Administrator หรือ Key Vault Crypto Officer |
| Service Account ของแอปพลิเคชัน (runtime) | Key Vault Crypto User (get, unwrapKey, wrapKey เท่านั้น — **ไม่ควรมีสิทธิ์ create/delete**) |
| DBA | ไม่ควรมีสิทธิ์เข้าถึง Key Vault เลย (เห็นเฉพาะ metadata/key path ใน SQL Server) |

**ตัวอย่างการกำหนดสิทธิ์ด้วย Azure CLI**:

```bash
az keyvault set-policy \
  --name mycompany-kv \
  --object-id <service-principal-object-id> \
  --key-permissions get unwrapKey wrapKey
```

### 7.2.2 Hardware Security Module (HSM) แบบ On-Premises

เหมาะสำหรับองค์กรที่มีข้อกำหนด compliance เข้มงวด (เช่น การเงิน, รัฐบาล) ที่ต้องการควบคุมฮาร์ดแวร์เอง:

- ต้องติดตั้ง CNG (Cryptography API: Next Generation) provider ของผู้ผลิต HSM (เช่น Thales Luna, SafeNet)
- Key path ในรูปแบบ `CNGProviderName/KeyName`
- ต้องมี HSM cluster/HA เพื่อไม่ให้เป็น single point of failure

### 7.2.3 Windows Certificate Store

เหมาะสำหรับ dev/test หรือองค์กรขนาดเล็กที่ยังไม่พร้อมลงทุน Key Vault/HSM:

- ต้อง export certificate (พร้อม private key, รูปแบบ .pfx) และนำไป import บนทุกเครื่องที่ต้องถอดรหัส (client machines, application servers)
- **ความเสี่ยง**: การกระจาย .pfx ไปหลายเครื่องเพิ่มพื้นที่การโจมตี (attack surface) ควรใช้ password ที่แข็งแรงในการปกป้องไฟล์ และลบไฟล์ทันทีหลัง import
- ไม่เหมาะกับ containerized/ephemeral environment (เช่น Kubernetes) เพราะต้องจัดการ certificate lifecycle เอง

## 7.3 การตั้งค่า Managed Identity สำหรับ Azure

สำหรับแอปพลิเคชันที่ deploy บน Azure (App Service, AKS, VM) ควรใช้ **Managed Identity** แทนการฝัง client secret:

```csharp
// ใช้ DefaultAzureCredential ซึ่งรองรับ Managed Identity อัตโนมัติเมื่อรันบน Azure
var credential = new DefaultAzureCredential();
var akvProvider = new SqlColumnEncryptionAzureKeyVaultProvider(credential);
```

ข้อดี: ไม่มี secret ให้รั่วไหล, หมุนเวียนโดยอัตโนมัติโดย Azure AD, ตรวจสอบสิทธิ์ผ่าน RBAC ได้ชัดเจน

## 7.4 การสำรองและกู้คืนคีย์ (Backup & Escrow)

- **Certificate Store**: export .pfx พร้อม private key แล้วเก็บในที่ปลอดภัยแยกจากระบบ (เช่น offline vault, safe deposit, หรือ secrets management เฉพาะสำหรับ escrow) พร้อมเข้ารหัสไฟล์ .pfx ด้วย passphrase ที่แข็งแรง
- **Azure Key Vault**: เปิดใช้ **Soft-Delete** และ **Purge Protection** เสมอสำหรับ vault ที่เก็บ Production CMK เพื่อป้องกันการลบถาวรโดยไม่ตั้งใจหรือโดยเจตนาร้าย และตั้งค่า geo-redundant backup ของ vault
- ทดสอบกระบวนการกู้คืน (restore drill) เป็นระยะ — การมี backup โดยไม่เคยทดสอบกู้คืนถือว่าไม่มี backup จริง
- เอกสารกระบวนการกู้คืนต้องแยกจากระบบที่จะกู้คืน (ห้ามเก็บไว้เฉพาะในระบบที่อาจล่มพร้อมกัน)

## 7.5 Rotation และ Versioning

ดูรายละเอียดกระบวนการหมุนเวียนคีย์แบบเต็มในบทที่ [8. การหมุนเวียนคีย์](08-key-rotation.md) แต่หลักการสำคัญด้าน key management ที่เกี่ยวข้อง:

- ควรกำหนดนโยบายอายุคีย์ (key lifetime) ตามข้อกำหนด compliance ขององค์กร (เช่น หมุนทุก 12 เดือน)
- Azure Key Vault รองรับการสร้าง key version ใหม่โดยไม่ต้องเปลี่ยน key path หลัก ทำให้ automation ง่ายขึ้น
- ควรมี monitoring แจ้งเตือนล่วงหน้าก่อนคีย์หมดอายุ (expiry) หากตั้งค่า `expires` ไว้ใน Key Vault

## 7.6 Monitoring และ Audit

- เปิด **Diagnostic Logging** บน Azure Key Vault ส่งไปยัง Log Analytics/SIEM เพื่อตรวจจับการเข้าถึงคีย์ที่ผิดปกติ (เช่น unwrapKey จาก IP/บัญชีที่ไม่คุ้นเคย)
- ตั้ง Alert เมื่อมีการพยายามเข้าถึงคีย์ที่ถูกปฏิเสธ (access denied) จำนวนมากผิดปกติ ซึ่งอาจบ่งชี้การโจมตี
- ทบทวนสิทธิ์การเข้าถึงคีย์เป็นระยะ (access review) อย่างน้อยทุกไตรมาส

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

#### 7.2.3.1 ขั้นตอนที่ 1: สร้าง certificate บน Windows Server

หากต้องใช้ Windows Certificate Store เป็น key store สำหรับ CMK บน Windows Server (เช่น SQL Server host หรือ application server) สามารถสร้าง certificate ใหม่ด้วย PowerShell ได้ดังนี้:

```powershell
$certPath = "C:\certs"
New-Item -ItemType Directory -Force -Path $certPath | Out-Null

$cert = New-SelfSignedCertificate `
  -CertStoreLocation "Cert:\LocalMachine\My" `
  -DnsName "sql.contoso.local" `
  -FriendlyName "AlwaysEncrypted-CMK" `
  -KeyExportPolicy Exportable `
  -NotAfter (Get-Date).AddYears(2)

$securePassword = ConvertTo-SecureString -String "P@ssw0rd!123" -Force -AsPlainText
Export-PfxCertificate -Cert $cert -FilePath "$certPath\alwaysencrypted-cmk.pfx" -Password $securePassword
Export-Certificate -Cert $cert -FilePath "$certPath\alwaysencrypted-cmk.cer"
```

สิ่งที่คำสั่งนี้ทำคือ:
- สร้าง certificate ใหม่ใน `Cert:\LocalMachine\My`
- ส่งออกไฟล์ `.pfx` ที่มี private key สำหรับใช้กับ CMK
- ส่งออกไฟล์ `.cer` สำหรับแจกจ่ายหรือ import ต่อ

#### 7.2.3.2 ขั้นตอนที่ 2: ตรวจสอบ certificate และเก็บ thumbprint

หลังสร้างเสร็จ ให้ตรวจสอบข้อมูลของ certificate เพื่อเอา `thumbprint` ไปใช้กับ SQL Server:

```powershell
$cert = Get-ChildItem Cert:\LocalMachine\My | Where-Object { $_.FriendlyName -eq "AlwaysEncrypted-CMK" } | Select-Object -First 1
$cert.Thumbprint
$cert.Subject
```

`thumbprint` คือค่าที่ SQL Server จะใช้ใน `KEY_PATH` เมื่อสร้าง Column Master Key (CMK)

#### 7.2.3.3 ขั้นตอนที่ 3: Import certificate ลงเครื่องอื่น ๆ

หากต้องใช้ certificate นี้ในเครื่องอื่น (เช่น client machine หรือ application server) ให้ import ไฟล์ `.pfx` ที่ส่งออกมาเข้าสู่ store ดังนี้:

```powershell
$pwd = Read-Host "กรอก password ของไฟล์ .pfx" -AsSecureString
Import-PfxCertificate -FilePath "C:\certs\alwaysencrypted-cmk.pfx" `
  -CertStoreLocation "Cert:\LocalMachine\My" `
  -Password $pwd
```

ถ้าต้องการ import ลง Current User แทน Local Machine ให้เปลี่ยนค่าเป็น `Cert:\CurrentUser\My`

#### 7.2.3.4 ขั้นตอนที่ 4: ใช้ certificate กับ SQL Server / Always Encrypted

เมื่อสร้าง Column Master Key (CMK) ใน SQL Server ให้ใช้ค่า `thumbprint` ที่ได้จากข้อก่อนหน้า:

```sql
CREATE COLUMN MASTER KEY MyCMK
WITH (
    KEY_STORE_PROVIDER_NAME = N'MSSQL_CERTIFICATE_STORE',
    KEY_PATH = N'LocalMachine/My/<thumbprint>'
);
```

ตัวอย่างโครงสร้างคำสั่งสร้าง Column Encryption Key (CEK):

```sql
CREATE COLUMN ENCRYPTION KEY MyCEK
WITH VALUES (
    COLUMN_MASTER_KEY = MyCMK,
    ALGORITHM = 'RSA_OAEP',
    ENCRYPTED_VALUE = 0x<hex>
);
```

ในงานจริง ค่าของ `ENCRYPTED_VALUE` มักจะถูกสร้างโดย client library หรือ driver ที่รองรับ Always Encrypted (เช่น .NET SqlClient) ไม่ได้เป็นค่าที่คุณสร้างด้วยมือโดยตรง

> สำหรับการใช้งานจริง ควรเก็บไฟล์ `.pfx` ในที่ปลอดภัย หลีกเลี่ยงการเก็บ password ใน script หรือคำสั่งที่เห็นได้ทั่วไป และควรลบไฟล์ชั่วคราวหลัง import แล้วถ้าหน่วยงานไม่ต้องการเก็บไว้ต่อ

> สำหรับสภาพแวดล้อม production ควรใช้ certificate จาก CA ภายในองค์กรหรือ public CA แทน self-signed และควรมีกระบวนการ backup, rotation และ revocation ที่ชัดเจน

### 7.2.4 Import Certificate และตั้งค่าพื้นฐาน OS บน Red Hat Enterprise Linux 9 (RHEL9)

เมื่อแอปพลิเคชันหรือ client runtime ที่รันบน RHEL9 ต้องเข้าถึง certificate-based CMK (เช่นไฟล์ .pfx/.p12 ที่ใช้กับ Always Encrypted) ควรทำตามขั้นตอนด้านล่างก่อนใช้งานจริง:

#### 7.2.4.1 ติดตั้งแพ็กเกจพื้นฐาน

```bash
sudo dnf update -y
sudo dnf install -y openssl ca-certificates unzip
```

#### 7.2.4.2 จัดเก็บไฟล์ certificate ในตำแหน่งที่ปลอดภัย

```bash
sudo mkdir -p /opt/alwaysencrypted/certs
sudo cp /path/to/client-certificate.pfx /opt/alwaysencrypted/certs/
sudo chown root:root /opt/alwaysencrypted/certs/client-certificate.pfx
sudo chmod 600 /opt/alwaysencrypted/certs/client-certificate.pfx
```

> ควรเก็บไฟล์ certificate ที่มี private key ไว้ในโฟลเดอร์ที่มีสิทธิ์เข้าถึงจำกัด และควรลบไฟล์ชั่วคราวหลังจาก import เสร็จเรียบร้อยแล้ว หากไม่มีความจำเป็นต้องเก็บต่อ

#### 7.2.4.3 แปลงไฟล์ PKCS#12 เป็น PEM สำหรับ OpenSSL

```bash
sudo openssl pkcs12 -in /opt/alwaysencrypted/certs/client-certificate.pfx -clcerts -nokeys \
  -out /etc/pki/tls/certs/client-cert.pem

sudo openssl pkcs12 -in /opt/alwaysencrypted/certs/client-certificate.pfx -nocerts -nodes \
  -out /etc/pki/tls/private/client-key.pem

sudo chown root:root /etc/pki/tls/certs/client-cert.pem /etc/pki/tls/private/client-key.pem
sudo chmod 644 /etc/pki/tls/certs/client-cert.pem
sudo chmod 600 /etc/pki/tls/private/client-key.pem
```

#### 7.2.4.4 ติดตั้ง certificate เข้า trust store ของระบบ

```bash
sudo cp /etc/pki/tls/certs/client-cert.pem /etc/pki/ca-trust/source/anchors/
sudo update-ca-trust
```

หากต้องใช้ certificate chain จาก CA ภายนอก ให้รวมไฟล์ CA bundle ลงในโฟลเดอร์ anchors ก่อนรันคำสั่ง `update-ca-trust`

#### 7.2.4.5 ตรวจสอบความถูกต้องของ certificate

```bash
sudo openssl x509 -in /etc/pki/tls/certs/client-cert.pem -noout -subject -issuer
sudo ls -l /etc/pki/tls/private/client-key.pem
```

#### 7.2.4.6 ตั้งค่าพื้นฐาน OS สำหรับความปลอดภัย

- รักษา SELinux ในโหมด `Enforcing` และตรวจสอบ context ของไฟล์ certificate:

```bash
sudo restorecon -Rv /etc/pki/tls /opt/alwaysencrypted/certs
```

- ใช้ `umask 077` หรือการตั้งค่า permission ที่จำกัดเมื่อสร้างไฟล์คีย์ใหม่
- จำกัดสิทธิ์การเข้าถึงไฟล์ private key ให้เฉพาะ root หรือ service account ที่จำเป็นจริง ๆ
- ถ้าต้องรันแอปพลิเคชันในระบบปิดให้ใช้ service account ที่ไม่ใช่ root เพื่อป้องกันการเปิดเผย private key

#### 7.2.4.7 สำหรับแอปพลิเคชัน Java/JDBC

หากแอปพลิเคชันใช้ Java และต้องการ import certificate ลงใน Java keystore ตัวอย่างคำสั่งคือ:

```bash
sudo dnf install -y java-17-openjdk-devel
sudo keytool -importkeystore \
  -srckeystore /opt/alwaysencrypted/certs/client-certificate.pfx \
  -srcstoretype PKCS12 \
  -destkeystore /etc/ssl/java/cacerts \
  -deststoretype JKS \
  -storepass changeit
```

> สำหรับสภาพแวดล้อม production ควรใช้ password ที่ซับซ้อนและเก็บไว้ใน secret management แทนการ hard-code ใน script หรือคำสั่งการติดตั้ง

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

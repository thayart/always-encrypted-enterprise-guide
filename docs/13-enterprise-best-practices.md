# 13. แนวทางปฏิบัติที่ดีสำหรับองค์กร (Enterprise Best Practices)

## 13.1 Governance และ Data Classification

- จัดตั้งกระบวนการ **Data Classification** อย่างเป็นทางการ ก่อนตัดสินใจว่าคอลัมน์ใดต้องเข้ารหัสด้วย Always Encrypted (อ้างอิงบทที่ 3.2.1)
- เชื่อมโยงการตัดสินใจเข้ารหัสกับข้อกำหนด compliance ที่เกี่ยวข้องขององค์กร (PCI-DSS, HIPAA, PDPA, GDPR, ISO 27001) และเก็บเอกสารเหตุผล (rationale) ไว้เพื่อการตรวจสอบ (audit)
- กำหนดเจ้าของข้อมูล (Data Owner) ที่ชัดเจนสำหรับแต่ละชุดข้อมูลอ่อนไหว แยกจากผู้ดูแลระบบฐานข้อมูล

## 13.2 Separation of Duties

หลักการสำคัญที่สุดของ Always Encrypted ในระดับองค์กรคือการแบ่งแยกบทบาทอย่างชัดเจน:

| บทบาท | สิทธิ์ที่ควรมี | สิทธิ์ที่ไม่ควรมี |
|---|---|---|
| DBA | จัดการ schema, performance, backup/restore, metadata ของ CMK/CEK | ไม่ควรมีสิทธิ์เข้าถึง CMK จริงใน key store |
| Security/Key Management Team | ดูแล Key Vault/HSM, กำหนดสิทธิ์เข้าถึงคีย์, ทำ key rotation | ไม่จำเป็นต้องมีสิทธิ์ sysadmin บนฐานข้อมูล |
| Application Service Account | สิทธิ์ unwrap/decrypt คีย์เฉพาะที่จำเป็นต่อการทำงาน (runtime only) | ไม่ควรมีสิทธิ์สร้าง/ลบคีย์ |
| Developer | เข้าถึงเฉพาะ dev/test environment ที่ใช้คีย์แยกจาก production | ไม่ควรมีสิทธิ์เข้าถึง production key store |

การแบ่งแยกนี้ทำให้ Always Encrypted บรรลุเป้าหมายด้านความปลอดภัยที่แท้จริง หากทุกบทบาทมีสิทธิ์เข้าถึงคีย์เหมือนกันหมด ประโยชน์ด้าน separation of duties จะหายไป

## 13.3 Environment Strategy (Dev / Staging / Production)

- ใช้ **CMK/CEK แยกกันอย่างเด็ดขาด** ระหว่าง dev, staging, และ production — ห้ามใช้คีย์ production ใน environment อื่นเด็ดขาด
- Dev/Test อาจใช้ Certificate Store แบบง่าย ในขณะที่ Production ควรใช้ Azure Key Vault/HSM ที่มี governance เข้มงวดกว่า
- ข้อมูลทดสอบ (test data) ในสภาพแวดล้อม dev ไม่ควรเป็นข้อมูลจริงที่คัดลอกมาจาก production แม้จะเข้ารหัสอยู่ก็ตาม (ใช้ data masking/synthetic data แทน)

## 13.4 การผสาน Always Encrypted เข้ากับ Defense-in-Depth

Always Encrypted ควรเป็น**หนึ่งในหลายชั้น**ของการป้องกัน ไม่ใช่มาตรการเดียว:

1. **Network Layer**: TLS/SSL สำหรับการเชื่อมต่อทั้งหมด, firewall/VNet isolation
2. **At-Rest Layer**: ใช้ TDE ร่วมกับ Always Encrypted เพื่อปกป้องไฟล์ฐานข้อมูล/backup แม้ในกรณีที่ metadata หลุดออกไป
3. **Column Layer**: Always Encrypted สำหรับคอลัมน์อ่อนไหวเฉพาะจุด
4. **Access Control Layer**: Row-Level Security (RLS), Dynamic Data Masking (DDM) สำหรับคอลัมน์ที่ไม่จำเป็นต้องเข้ารหัสเต็มรูปแบบแต่ต้องการจำกัดการมองเห็น
5. **Application Layer**: การยืนยันตัวตนและสิทธิ์ที่รัดกุมสำหรับผู้ใช้ปลายทาง, secure coding practice ป้องกัน SQL injection
6. **Monitoring Layer**: Audit logging, SIEM integration, anomaly detection บนการเข้าถึงคีย์และข้อมูล

> หมายเหตุ: Dynamic Data Masking (DDM) **ไม่ใช่** การเข้ารหัสจริง เป็นเพียงการซ่อนข้อมูลจาก query ทั่วไป — ผู้ที่มีสิทธิ์สูงยังคงเห็นข้อมูลจริงได้ ไม่ควรใช้ DDM แทน Always Encrypted สำหรับข้อมูลที่ต้องปกป้องอย่างจริงจัง

## 13.5 Change Management และ Documentation

- บันทึกเอกสาร **Key Inventory**: รายการ CMK/CEK ทั้งหมด, key store ที่ใช้, คอลัมน์ที่ผูกอยู่, วันที่สร้าง, วันที่หมุนเวียนล่าสุด, ผู้รับผิดชอบ
- จัดทำ **Runbook** สำหรับ: การเพิ่มคอลัมน์เข้ารหัสใหม่, การหมุนเวียนคีย์ตามรอบ, การกู้คืนกรณีฉุกเฉิน (ดูบทที่ 8)
- ทุกการเปลี่ยนแปลงเกี่ยวกับคีย์ (สร้าง/หมุนเวียน/ลบ) ควรผ่านกระบวนการ change management ปกติขององค์กร (change request, approval, testing)

## 13.6 การตรวจสอบและ Compliance Audit

- เก็บ audit log ของการเข้าถึงคีย์ (Key Vault Diagnostic Logs) ไว้อย่างน้อยตามระยะเวลาที่ compliance กำหนด (เช่น PCI-DSS กำหนด 1 ปี โดยมี 3 เดือนล่าสุดที่ต้อง readily available)
- ทำ Access Review เป็นระยะ (อย่างน้อยทุกไตรมาส) เพื่อยืนยันว่าสิทธิ์เข้าถึงคีย์ยังตรงกับความจำเป็นจริง (least privilege) — ถอดสิทธิ์ของบัญชี/แอปพลิเคชันที่เลิกใช้งานแล้ว
- เตรียมรายงานสำหรับ auditor ภายนอก: รายการคอลัมน์เข้ารหัส, กลไกจัดการคีย์, หลักฐานการแบ่งแยกหน้าที่ (separation of duties)

## 13.7 Incident Response

จัดทำแผนตอบสนองเหตุการณ์ (Incident Response Plan) ที่ครอบคลุมสถานการณ์เฉพาะของ Always Encrypted:

- คีย์รั่วไหลหรือถูกขโมย (ดูบทที่ 8.5)
- Key Store ล่มหรือเข้าถึงไม่ได้ (แผน failover/DR)
- แอปพลิเคชันที่มีสิทธิ์เข้าถึงคีย์ถูกบุกรุก (compromised credential)
- การสูญเสียคีย์ถาวรโดยไม่มี backup (data loss scenario — ต้องมีแผนป้องกันตั้งแต่ต้นตามบทที่ 7.4)

## 13.8 CI/CD และ Infrastructure as Code

- จัดการ schema ของคอลัมน์เข้ารหัส (metadata) ผ่าน source control เช่นเดียวกับ schema อื่น ๆ (SSDT/DACPAC, Flyway, Liquibase)
- แยกขั้นตอน "provision key metadata" ออกจาก pipeline การ deploy อัตโนมัติทั่วไป — ควรมี manual approval gate สำหรับขั้นตอนที่กระทบคีย์การเข้ารหัส
- ใช้ Infrastructure as Code (Terraform/Bicep/ARM) สำหรับการสร้าง Key Vault และกำหนดสิทธิ์ เพื่อให้ตรวจสอบ (review) การเปลี่ยนแปลงสิทธิ์ผ่าน pull request ได้
- ห้าม hardcode key path, connection string ที่มี credential ไว้ใน source code — ใช้ secret management (Azure Key Vault references, environment variables ผ่าน pipeline secrets)

## 13.9 Training และ Awareness

- ฝึกอบรมทีมพัฒนาให้เข้าใจข้อจำกัดของ query pattern (บทที่ 10) ก่อนเริ่มออกแบบ feature ใหม่ที่แตะข้อมูลเข้ารหัส เพื่อลดการค้นพบปัญหาช้าไปในขั้นตอน production
- ฝึกอบรม DBA/Security team เรื่องกระบวนการหมุนเวียนคีย์และการตอบสนองเหตุการณ์ฉุกเฉินอย่างสม่ำเสมอ (tabletop exercise)

## 13.10 Checklist สรุปก่อนขึ้น Production

- [ ] ทำ Data Classification และระบุคอลัมน์ที่ต้องเข้ารหัสครบถ้วน
- [ ] เลือก Key Store ที่เหมาะสม (แนะนำ Azure Key Vault/HSM สำหรับ Production)
- [ ] ออกแบบ Deterministic/Randomized ต่อคอลัมน์อย่างเหมาะสม พร้อมประเมินความจำเป็นของ Secure Enclave
- [ ] ตั้งค่าสิทธิ์แบบ Least Privilege และ Separation of Duties ครบถ้วน
- [ ] ปรับปรุงโค้ดแอปพลิเคชันทั้งหมดที่เกี่ยวข้อง (parameterized query, connection string)
- [ ] ทดสอบ Performance/Load Test เทียบก่อน-หลังเข้ารหัส
- [ ] จัดทำแผน Backup/Escrow ของคีย์ และทดสอบกระบวนการกู้คืนแล้ว
- [ ] จัดทำ Runbook สำหรับ Key Rotation และ Incident Response
- [ ] เปิด Audit Logging บน Key Store และตั้งค่า Alert ที่เหมาะสม
- [ ] ตรวจสอบ Reporting/BI/ETL tools ทั้งหมดที่เข้าถึงข้อมูลนี้ว่ารองรับ Always Encrypted

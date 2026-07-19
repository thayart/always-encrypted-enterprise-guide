# คู่มือ Always Encrypted สำหรับ SQL Server (Enterprise Guide)

คู่มือฉบับสมบูรณ์สำหรับการวางแผน ติดตั้ง และดูแลรักษาฟีเจอร์ **Always Encrypted** ของ Microsoft SQL Server ในระดับองค์กร ครอบคลุมตั้งแต่แนวคิดพื้นฐาน สถาปัตยกรรม การติดตั้งจริง ไปจนถึงแนวทางปฏิบัติที่ดี (Best Practices) สำหรับสภาพแวดล้อม Production

## เกี่ยวกับ Always Encrypted

Always Encrypted เป็นฟีเจอร์ด้านความปลอดภัยของ SQL Server (ตั้งแต่ 2016 เป็นต้นมา) และ Azure SQL Database ที่ออกแบบมาเพื่อปกป้องข้อมูลอ่อนไหว (เช่น เลขบัตรเครดิต, เลขบัตรประชาชน, ข้อมูลสุขภาพ) โดยข้อมูลจะถูก**เข้ารหัสที่ฝั่งไคลเอนต์** (client-side, ผ่าน driver) ก่อนส่งไปยังฐานข้อมูล ทำให้แม้แต่ผู้ดูแลระบบฐานข้อมูล (DBA) หรือผู้ที่เข้าถึงเซิร์ฟเวอร์โดยตรงก็ไม่สามารถเห็นข้อมูลต้นฉบับ (plaintext) ได้

## สารบัญ

1. [ภาพรวมและแนวคิดพื้นฐาน](docs/01-overview.md)
2. [สถาปัตยกรรมและองค์ประกอบหลัก](docs/02-architecture.md)
3. [ข้อกำหนดเบื้องต้นและการวางแผน](docs/03-prerequisites.md)
4. [การติดตั้งผ่าน SSMS (Wizard)](docs/04-setup-ssms.md)
5. [การติดตั้งผ่าน T-SQL และ PowerShell](docs/05-setup-tsql-powershell.md)
6. [การพัฒนาแอปพลิเคชันให้รองรับ Always Encrypted](docs/06-application-development.md)
7. [การบริหารจัดการคีย์ (Key Management)](docs/07-key-management.md)
8. [การหมุนเวียนคีย์ (Key Rotation)](docs/08-key-rotation.md)
9. [Always Encrypted with Secure Enclaves](docs/09-secure-enclaves.md)
10. [ข้อจำกัดและสิ่งที่ต้องพิจารณา](docs/10-limitations.md)
11. [ผลกระทบด้านประสิทธิภาพ (Performance)](docs/11-performance.md)
12. [การแก้ไขปัญหา (Troubleshooting)](docs/12-troubleshooting.md)
13. [แนวทางปฏิบัติที่ดีสำหรับองค์กร (Enterprise Best Practices)](docs/13-enterprise-best-practices.md)
14. [เอกสารอ้างอิง](docs/14-references.md)

## เหมาะสำหรับใคร

- DBA และ Database Engineer ที่ต้องติดตั้งและดูแล Always Encrypted
- Application Developer ที่ต้องปรับโค้ดให้ทำงานร่วมกับคอลัมน์ที่เข้ารหัส
- Security Architect / Compliance Officer ที่ต้องประเมินความเสี่ยงและออกแบบนโยบายจัดการคีย์
- ทีม DevOps ที่ต้องดูแล pipeline การ deploy schema และ key rotation

## ขอบเขต

คู่มือนี้ครอบคลุม SQL Server 2016 ขึ้นไป (on-premises) และ Azure SQL Database/Managed Instance โดยเน้นสถานการณ์ใช้งานจริงในองค์กร เช่น การจัดการคีย์ด้วย Azure Key Vault, HSM, และ Windows Certificate Store, การหมุนเวียนคีย์แบบไม่มี downtime, และแนวทางตรวจสอบเพื่อรองรับมาตรฐาน compliance เช่น PCI-DSS และ HIPAA

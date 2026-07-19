# 14. เอกสารอ้างอิง

## เอกสารทางการของ Microsoft

- Always Encrypted (Database Engine) — Microsoft Learn: `learn.microsoft.com/sql/relational-databases/security/encryption/always-encrypted-database-engine`
- Always Encrypted with secure enclaves — Microsoft Learn: `learn.microsoft.com/sql/relational-databases/security/encryption/always-encrypted-enclaves`
- Configure Always Encrypted using PowerShell: `learn.microsoft.com/sql/relational-databases/security/encryption/configure-always-encrypted-using-powershell`
- Configure Always Encrypted using SSMS: `learn.microsoft.com/sql/relational-databases/security/encryption/configure-always-encrypted-using-sql-server-management-studio`
- Develop applications using Always Encrypted: `learn.microsoft.com/sql/relational-databases/security/encryption/develop-using-always-encrypted-with-net-framework-data-provider`
- Always Encrypted key management using PowerShell: `learn.microsoft.com/sql/relational-databases/security/encryption/overview-of-key-management-for-always-encrypted`
- Rotate Always Encrypted keys using PowerShell: `learn.microsoft.com/sql/relational-databases/security/encryption/rotate-always-encrypted-keys-using-powershell`
- Transparent Data Encryption (TDE) — เปรียบเทียบกับ Always Encrypted: `learn.microsoft.com/sql/relational-databases/security/encryption/transparent-data-encryption`

## เครื่องมือที่เกี่ยวข้อง

- `Microsoft.Data.SqlClient` (NuGet package สำหรับ .NET)
- Microsoft JDBC Driver for SQL Server
- Microsoft ODBC Driver for SQL Server
- PowerShell module: `SqlServer` (มี cmdlet `*-SqlColumnEncryptionKey`, `*-SqlColumnMasterKey`, `Set-SqlColumnEncryption`)
- Azure Key Vault และ Azure Attestation (สำหรับ Azure SQL Secure Enclaves)

## หมายเหตุการดูแลคู่มือนี้

คู่มือนี้อ้างอิงพฤติกรรมและความสามารถของ Always Encrypted ณ ช่วง SQL Server 2019-2022 และ Azure SQL Database ปัจจุบัน เนื่องจาก Microsoft มีการอัปเดตฟีเจอร์ (โดยเฉพาะส่วน Secure Enclaves และ driver รุ่นใหม่) อย่างต่อเนื่อง จึงควรตรวจสอบเอกสารทางการล่าสุดประกอบก่อนนำไปใช้งานจริงในระบบสำคัญเสมอ โดยเฉพาะในหัวข้อ:

- Compatibility matrix ของ driver แต่ละภาษา/แพลตฟอร์ม
- ข้อจำกัดของ Secure Enclave ที่อาจมีการเพิ่มความสามารถในเวอร์ชันใหม่
- การเปลี่ยนแปลงด้าน pricing/tier ของ Azure Key Vault และ Managed HSM

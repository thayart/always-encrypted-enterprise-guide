# 5. การติดตั้งผ่าน T-SQL และ PowerShell

การใช้ T-SQL และ PowerShell เหมาะสำหรับ Production, automation, และ CI/CD pipeline เพราะสามารถ script, review, และ version control ได้

> **ข้อจำกัดสำคัญ**: คำสั่ง T-SQL (`CREATE COLUMN MASTER KEY`, `CREATE COLUMN ENCRYPTION KEY`, `ALTER TABLE ... ALTER COLUMN ... ENCRYPTED WITH`) จะสร้าง**เฉพาะ metadata** และตั้งค่าคอลัมน์เท่านั้น แต่ **T-SQL ล้วนไม่สามารถเข้ารหัสข้อมูลที่มีอยู่แล้วในตารางได้** (เพราะ SQL Server ไม่มีสิทธิ์เข้าถึง CMK/plaintext CEK) การเข้ารหัสข้อมูลจริงต้องทำผ่าน PowerShell module `SqlServer` (cmdlet ที่เชื่อมต่อผ่าน driver ที่รองรับ) หรือ bcp/แอปพลิเคชันที่เขียนขึ้นเอง

## 5.1 ขั้นตอนด้วย T-SQL (สำหรับตารางใหม่/ยังไม่มีข้อมูล)

### 5.1.1 สร้าง Column Master Key metadata

```sql
-- ตัวอย่าง: CMK อ้างอิง certificate ใน Windows Certificate Store
CREATE COLUMN MASTER KEY [CMK_Auto1]
WITH (
    KEY_STORE_PROVIDER_NAME = N'MSSQL_CERTIFICATE_STORE',
    KEY_PATH = N'CurrentUser/My/A66BFE1B7BAA02EE97EAF7C6A2FD5906D149936C'
);
```

```sql
-- ตัวอย่าง: CMK อ้างอิง Azure Key Vault
CREATE COLUMN MASTER KEY [CMK_AKV]
WITH (
    KEY_STORE_PROVIDER_NAME = N'AZURE_KEY_VAULT',
    KEY_PATH = N'https://mycompany-kv.vault.azure.net/keys/CMK-Prod/8f8f8f8f8f8f4f8f8f8f8f8f8f8f8f8f'
);
```

### 5.1.2 สร้าง Column Encryption Key

CEK ต้องถูกสร้างด้วยค่าที่เข้ารหัสแล้ว (encrypted value) ซึ่งได้มาจากการเรียก cmdlet PowerShell หรือ SSMS wizard ก่อน เนื่องจาก T-SQL เองไม่มีสิทธิ์เข้าถึง CMK เพื่อสร้างค่าที่เข้ารหัสได้:

```sql
CREATE COLUMN ENCRYPTION KEY [CEK_Auto1]
WITH VALUES (
    COLUMN_MASTER_KEY = [CMK_Auto1],
    ALGORITHM = 'RSA_OAEP',
    ENCRYPTED_VALUE = 0x016E000001630075...  -- ค่าที่ได้จาก Get-SqlColumnEncryptionKeyEncryptedValue
);
```

> ค่า `ENCRYPTED_VALUE` หาได้จาก PowerShell cmdlet `Get-SqlColumnEncryptionKeyEncryptedValue` (ดูหัวข้อ 5.2.1) — ไม่แนะนำให้เขียน T-SQL นี้มือ ควรให้ script generate ให้

### 5.1.3 ตั้งค่าคอลัมน์ให้เข้ารหัส (เฉพาะตารางที่ยังไม่มีข้อมูล หรือกำลังสร้างใหม่)

```sql
CREATE TABLE dbo.Customer (
    CustomerId      INT IDENTITY PRIMARY KEY,
    NationalId      CHAR(13) COLLATE Latin1_General_BIN2
        ENCRYPTED WITH (
            COLUMN_ENCRYPTION_KEY = [CEK_Auto1],
            ENCRYPTION_TYPE = DETERMINISTIC,
            ALGORITHM = 'AEAD_AES_256_CBC_HMAC_SHA256'
        ) NOT NULL,
    CreditCardNumber VARCHAR(19) COLLATE Latin1_General_BIN2
        ENCRYPTED WITH (
            COLUMN_ENCRYPTION_KEY = [CEK_Auto1],
            ENCRYPTION_TYPE = RANDOMIZED,
            ALGORITHM = 'AEAD_AES_256_CBC_HMAC_SHA256'
        ) NULL,
    FullName        NVARCHAR(100) NOT NULL  -- ไม่เข้ารหัส
);
```

## 5.2 ขั้นตอนด้วย PowerShell (สำหรับตารางที่มีข้อมูลอยู่แล้ว — วิธีที่แนะนำสำหรับ Production)

ต้องติดตั้ง PowerShell module `SqlServer` ก่อน:

```powershell
Install-Module -Name SqlServer -Scope CurrentUser -AllowClobber
```

### 5.2.1 สร้าง CMK และ CEK ด้วย PowerShell

```powershell
Import-Module SqlServer

$serverName   = "myserver.database.windows.net"
$databaseName = "MyProdDb"

# เชื่อมต่อฐานข้อมูล
$connStr = "Server=$serverName;Database=$databaseName;Integrated Security=True;"
$database = Get-SqlDatabase -ConnectionString $connStr

# กำหนด Key Store Provider — ตัวอย่างนี้ใช้ Azure Key Vault
$akvKey = New-SqlAzureKeyVaultColumnMasterKeySettings `
    -KeyURL "https://mycompany-kv.vault.azure.net/keys/CMK-Prod/8f8f8f8f8f8f4f8f8f8f8f8f8f8f8f8f"

# สร้าง Column Master Key metadata ในฐานข้อมูล
New-SqlColumnMasterKey -Name "CMK_Prod" -InputObject $database `
    -ColumnMasterKeySettings $akvKey

# สร้าง Column Encryption Key (จะถูกเข้ารหัสด้วย CMK ที่สร้างไว้)
New-SqlColumnEncryptionKey -Name "CEK_Prod" -InputObject $database `
    -ColumnMasterKey "CMK_Prod"
```

### 5.2.2 เข้ารหัสคอลัมน์ที่มีข้อมูลอยู่แล้ว (Offline Encryption)

```powershell
$dbConn = "Server=$serverName;Database=$databaseName;Integrated Security=True;"

# กำหนดค่าการเข้ารหัสของแต่ละคอลัมน์
$cesc1 = New-SqlColumnEncryptionSettings `
    -ColumnName "dbo.Customer.NationalId" `
    -EncryptionType "Deterministic" `
    -EncryptionKey "CEK_Prod"

$cesc2 = New-SqlColumnEncryptionSettings `
    -ColumnName "dbo.Customer.CreditCardNumber" `
    -EncryptionType "Randomized" `
    -EncryptionKey "CEK_Prod"

# รันการเข้ารหัสจริง (offline — จะ lock ตารางระหว่างดำเนินการ)
Set-SqlColumnEncryption -ColumnEncryptionSettings $cesc1, $cesc2 `
    -InputObject $database
```

### 5.2.3 Online Encryption (ลดผลกระทบต่อ Production)

สำหรับ SQL Server 2019+ / Azure SQL รองรับ **Online Encryption** ซึ่งลด downtime โดยอนุญาตให้อ่าน-เขียนตารางระหว่างกระบวนการเข้ารหัสได้ (ใช้กลไกคล้าย online index rebuild):

```powershell
Set-SqlColumnEncryption -ColumnEncryptionSettings $cesc1, $cesc2 `
    -InputObject $database `
    -OnlineEncryption
```

> **หมายเหตุ**: Online Encryption ใช้เวลานานกว่า offline และใช้ tempdb/log space มากกว่า ควรทดสอบ capacity ก่อนรันจริงใน production

## 5.3 การตรวจสอบผลลัพธ์

```sql
SELECT
    t.name AS TableName,
    c.name AS ColumnName,
    c.encryption_type_desc,
    cek.name AS CEKName,
    cmk.name AS CMKName,
    cmk.key_store_provider_name
FROM sys.columns c
JOIN sys.tables t ON c.object_id = t.object_id
JOIN sys.column_encryption_keys cek ON c.column_encryption_key_id = cek.column_encryption_key_id
JOIN sys.column_encryption_key_values cekv ON cek.column_encryption_key_id = cekv.column_encryption_key_id
JOIN sys.column_master_keys cmk ON cekv.column_master_key_id = cmk.column_master_key_id
WHERE c.encryption_type IS NOT NULL;
```

## 5.4 Automation ผ่าน CI/CD

สำหรับองค์กรที่ deploy schema ผ่าน pipeline (Azure DevOps, GitHub Actions, Jenkins):

- เก็บสคริปต์ PowerShell/T-SQL เป็นส่วนหนึ่งของ database migration scripts (เช่นใช้ร่วมกับ DACPAC, Flyway, หรือ SSDT)
- **ห้าม** เก็บค่า `ENCRYPTED_VALUE` หรือ credential ของ Key Vault ไว้ใน source control แบบ plaintext — ใช้ pipeline secret variable หรือ managed identity แทน
- แยก pipeline step สำหรับ "provision key metadata" ออกจาก "encrypt data" เนื่องจากขั้นตอนหลังมักต้องรันแบบ manual/scheduled ใน maintenance window ไม่ใช่ทุกครั้งที่ deploy
- ทดสอบ script ใน staging ที่มีขนาดข้อมูลใกล้เคียง production ก่อนเสมอ เพื่อประเมินเวลาที่ใช้จริง

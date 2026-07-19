# 12. การแก้ไขปัญหา (Troubleshooting)

## 12.1 ตารางปัญหาที่พบบ่อย

| อาการ | สาเหตุที่เป็นไปได้ | วิธีแก้ |
|---|---|---|
| ได้ค่า binary/base64 แปลก ๆ แทนข้อมูลจริง | Connection string ไม่ได้เปิด `Column Encryption Setting=Enabled` | เพิ่ม property นี้ใน connection string ของทุกแอปพลิเคชันที่เกี่ยวข้อง |
| `Failed to decrypt column encryption key` | Client ไม่มีสิทธิ์เข้าถึง CMK ใน key store (certificate ไม่ได้ import / ไม่มีสิทธิ์บน Key Vault) | ตรวจสอบ certificate store บนเครื่อง client หรือ access policy บน Key Vault |
| `Operand type clash between X and Y` | ชนิด/ขนาด/collation ของพารามิเตอร์ไม่ตรงกับคอลัมน์เข้ารหัส | ตรวจสอบ mapping data type ให้ตรงเป๊ะ รวมถึง collation `_BIN2` |
| `Encryption scheme mismatch for columns/variables` | พยายาม query ด้วย operation ที่ไม่รองรับ (เช่น `LIKE` บนคอลัมน์ที่ไม่มี enclave) หรือ join ระหว่างคอลัมน์ที่ใช้ CEK/algorithm ต่างกัน | ปรับ query pattern หรือใช้ CEK เดียวกัน/เปิด enclave |
| Query ที่เคยทำงานได้ กลับ error หลังเข้ารหัสคอลัมน์ | Dynamic SQL หรือ ORM ที่ inline literal แทน parameter | เปลี่ยนเป็น parameterized query (`sp_executesql`) |
| `Internal error. An exception occurred while enclave...` | Attestation ล้มเหลว หรือ enclave configuration ไม่ถูกต้อง | ตรวจสอบ Attestation URL, HGS service, หรือ enclave type ที่ config ไว้ |
| Performance ช้าลงมากหลังเข้ารหัส | Query pattern ที่ต้อง table scan แทน index seek, หรือ metadata cache ไม่ warm | ทบทวน query plan, ตรวจสอบว่าคอลัมน์ index เป็น deterministic, ดูบทที่ 11 |
| PowerShell `New-SqlColumnEncryptionKey` ล้มเหลว | ไม่มีสิทธิ์เข้าถึง CMK ที่กำลังใช้เข้ารหัส CEK จากเครื่องที่รัน PowerShell | ตรวจสอบสิทธิ์และเครือข่ายเข้าถึง key store จากเครื่องที่รันคำสั่ง |
| แอปพลิเคชันบางตัวใช้งานได้ บางตัวใช้งานไม่ได้หลัง rotation | บาง client cache metadata เก่า หรือยังไม่ได้อัปเดตสิทธิ์เข้าถึง CMK ใหม่ | Restart application pool/service เพื่อล้าง cache, ตรวจสอบสิทธิ์ CMK ใหม่ครบทุกตัว |

## 12.2 ขั้นตอนการวินิจฉัยปัญหาทั่วไป

### ขั้นที่ 1: ยืนยันว่า Connection String ถูกต้อง

ตรวจสอบว่ามี `Column Encryption Setting=Enabled` (หรือเทียบเท่าใน driver อื่น เช่น `columnEncryptionSetting=Enabled` สำหรับ JDBC, `ColumnEncryption=Enabled` สำหรับ ODBC)

### ขั้นที่ 2: ยืนยันสิทธิ์เข้าถึง Key Store จากเครื่อง Client

```powershell
# ตรวจสอบว่า certificate สำหรับ CMK มีอยู่ใน store และมี private key
Get-ChildItem -Path Cert:\CurrentUser\My | Where-Object { $_.Thumbprint -eq "<thumbprint>" }
```

สำหรับ Azure Key Vault ให้ตรวจสอบด้วย Azure CLI ว่า service principal/managed identity ที่แอปพลิเคชันใช้มีสิทธิ์ `get`, `unwrapKey`:

```bash
az keyvault key show --vault-name mycompany-kv --name CMK-Prod
az role assignment list --scope /subscriptions/<sub>/resourceGroups/<rg>/providers/Microsoft.KeyVault/vaults/mycompany-kv
```

### ขั้นที่ 3: ตรวจสอบ Metadata ในฐานข้อมูล

```sql
SELECT * FROM sys.column_master_keys;
SELECT * FROM sys.column_encryption_keys;
SELECT
    t.name AS TableName, c.name AS ColumnName,
    c.encryption_type_desc, cek.name AS CEKName
FROM sys.columns c
JOIN sys.tables t ON c.object_id = t.object_id
LEFT JOIN sys.column_encryption_keys cek ON c.column_encryption_key_id = cek.column_encryption_key_id
WHERE c.encryption_type IS NOT NULL;
```

### ขั้นที่ 4: ทดสอบด้วย SSMS โดยตรง

เปิด SSMS ใหม่ → **Options → Query Execution → SQL Server** → เปิด **"Enable Parameterization for Always Encrypted"** แล้วรัน query ทดสอบผ่าน connection ที่เปิด **"Always Encrypted"** ใน connection properties เพื่อแยกว่าปัญหาอยู่ที่ตัวข้อมูล/schema หรือที่โค้ดแอปพลิเคชัน

### ขั้นที่ 5: ตรวจสอบ Log ฝั่ง Driver/แอปพลิเคชัน

- .NET: เปิด verbose logging ของ `Microsoft.Data.SqlClient` ผ่าน `EventSource` หรือ trace listener
- JDBC: เปิด `loggerLevel` เป็น `FINEST` ใน connection properties เพื่อดู log การเรียก key store
- ตรวจสอบ Network trace (เช่น Wireshark/Fiddler) หากสงสัยปัญหาการเชื่อมต่อ Key Vault

## 12.3 คำสั่ง Diagnostic ที่มีประโยชน์

```sql
-- ตรวจสอบ enclave type ปัจจุบันของ instance
SELECT SERVERPROPERTY('EnclaveType');

-- ตรวจสอบว่า attestation ทำงานถูกต้องหรือไม่ (ผ่าน Extended Events หรือ error log)
SELECT * FROM sys.dm_exec_query_stats WHERE ... -- ใช้ร่วมกับ Extended Events session ที่ดักจับ enclave-related errors
```

```sql
-- เปิด Extended Events เพื่อตรวจจับ error ที่เกี่ยวกับ Always Encrypted
CREATE EVENT SESSION [AlwaysEncrypted_Diag] ON SERVER
ADD EVENT sqlserver.error_reported (
    WHERE ([severity] >= 11)
)
ADD TARGET package0.event_file(SET filename = N'AlwaysEncrypted_Diag.xel');
ALTER EVENT SESSION [AlwaysEncrypted_Diag] ON SERVER STATE = START;
```

## 12.4 เมื่อควรขอความช่วยเหลือจาก Microsoft Support

พิจารณาเปิด support case เมื่อ:

- Attestation service (HGS) ล้มเหลวโดยไม่ทราบสาเหตุหลังตรวจสอบ configuration ครบถ้วนแล้ว
- Query plan ผิดปกติรุนแรงที่ไม่สามารถอธิบายได้ด้วยข้อจำกัดปกติของ Always Encrypted
- สงสัยว่ามี bug ใน driver เวอร์ชันที่ใช้ (ควรตรวจสอบ release notes/known issues ก่อนเสมอ)

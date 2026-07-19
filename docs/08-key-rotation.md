# 8. การหมุนเวียนคีย์ (Key Rotation)

การหมุนเวียนคีย์เป็นสิ่งจำเป็นตามหลัก security hygiene และมักถูกกำหนดโดยข้อบังคับ compliance (เช่น หมุนทุก 1 ปี) รวมถึงกรณีฉุกเฉินเมื่อสงสัยว่าคีย์รั่วไหล

## 8.1 สองระดับของการหมุนเวียนคีย์

| ระดับ | คืออะไร | ความถี่ที่มักใช้ | Downtime |
|---|---|---|---|
| **CMK Rotation** | เปลี่ยนกุญแจที่ใช้ปกป้อง CEK (ไม่กระทบ ciphertext ของข้อมูลจริง) | บ่อยกว่า (เช่น ทุก 6-12 เดือน) | แทบไม่มี |
| **CEK Rotation** | เปลี่ยนกุญแจที่เข้ารหัสข้อมูลจริง (ต้อง re-encrypt ข้อมูลทั้งหมดในคอลัมน์) | น้อยกว่า (เช่น ทุก 1-2 ปี หรือเมื่อสงสัยรั่วไหล) | มี (เว้นแต่ใช้ enclave) |

## 8.2 การหมุนเวียน CMK (Column Master Key Rotation)

CMK rotation **ไม่ต้องแตะข้อมูลจริงในตาราง** เพราะเป็นเพียงการเปลี่ยนกุญแจที่ใช้ห่อหุ้ม (wrap) CEK เท่านั้น ขั้นตอนคือ:

1. สร้าง CMK ใหม่ (key version ใหม่ใน Key Vault หรือ certificate ใหม่)
2. ประกาศ CMK ใหม่ใน SQL Server metadata (`CREATE COLUMN MASTER KEY`)
3. เข้ารหัส CEK เดิมด้วย CMK ใหม่ (จะได้ CEK ที่มีสอง encrypted value พร้อมกันชั่วคราว — เข้ารหัสด้วยทั้ง CMK เก่าและใหม่)
4. อัปเดต connection ของแอปพลิเคชันทั้งหมดให้เข้าถึง CMK ใหม่ได้
5. ลบ encrypted value ที่ผูกกับ CMK เก่าออก และลบ CMK เก่า (cleanup)

### ตัวอย่างด้วย PowerShell

```powershell
Import-Module SqlServer

$connStr = "Server=myserver;Database=MyProdDb;Integrated Security=True;"
$database = Get-SqlDatabase -ConnectionString $connStr

# ขั้นที่ 1-2: สร้าง CMK ใหม่ (ตัวอย่างใช้ Key Vault key version ใหม่)
$newAkvKey = New-SqlAzureKeyVaultColumnMasterKeySettings `
    -KeyURL "https://mycompany-kv.vault.azure.net/keys/CMK-Prod-2026/<new-version-guid>"

New-SqlColumnMasterKey -Name "CMK_Prod_2026" -InputObject $database `
    -ColumnMasterKeySettings $newAkvKey

# ขั้นที่ 3: เข้ารหัส CEK ที่มีอยู่ด้วย CMK ใหม่ (เพิ่ม encrypted value ที่สอง)
Add-SqlColumnEncryptionKeyValue -InputObject $database `
    -ColumnEncryptionKeyName "CEK_Prod" `
    -ColumnMasterKeyName "CMK_Prod_2026"

# ตอนนี้ CEK_Prod ถูกเข้ารหัสด้วยทั้ง CMK_Prod (เก่า) และ CMK_Prod_2026 (ใหม่)
# แอปพลิเคชันทุกตัวสามารถถอดรหัสได้โดยใช้ CMK ตัวใดตัวหนึ่ง — ช่วงนี้คือ "grace period"

# ขั้นที่ 5 (หลังยืนยันว่าทุกแอปพลิเคชันย้ายไปใช้ CMK ใหม่แล้ว): ลบ encrypted value เก่าและ CMK เก่า
Remove-SqlColumnEncryptionKeyValue -InputObject $database `
    -ColumnEncryptionKeyName "CEK_Prod" `
    -ColumnMasterKeyName "CMK_Prod"

Remove-SqlColumnMasterKey -Name "CMK_Prod" -InputObject $database
```

> **สำคัญ**: อย่าลบ CMK เก่าทันที ให้เว้นช่วง "grace period" ที่ CEK ถูกเข้ารหัสด้วยทั้งคีย์เก่าและใหม่พร้อมกัน จนกว่าจะมั่นใจว่าทุก client ทุก environment (รวม cache) เปลี่ยนไปใช้คีย์ใหม่แล้วจริง ๆ

## 8.3 การหมุนเวียน CEK (Column Encryption Key Rotation)

CEK rotation ต้อง **ถอดรหัสข้อมูลเดิมด้วย CEK เก่าแล้วเข้ารหัสใหม่ด้วย CEK ใหม่** ซึ่งเป็น operation ที่หนักกว่า CMK rotation มาก

### วิธีที่ 1: แบบดั้งเดิม (ไม่มี Secure Enclave)

ใช้ `Set-SqlColumnEncryption` ของ PowerShell คล้ายกับตอน provisioning ครั้งแรก โดยระบุ CEK ใหม่:

```powershell
$cesc = New-SqlColumnEncryptionSettings `
    -ColumnName "dbo.Customer.NationalId" `
    -EncryptionType "Deterministic" `
    -EncryptionKey "CEK_Prod_v2"

Set-SqlColumnEncryption -ColumnEncryptionSettings $cesc `
    -InputObject $database `
    -OnlineEncryption
```

ข้อจำกัด: ต้องดึงข้อมูลออกมาถอดรหัสที่ client แล้วเข้ารหัสใหม่ทีละแถว จึงใช้เวลานานและมีภาระ I/O สูงตามขนาดตาราง เหมาะกับการทำนอกเวลาทำการ

### วิธีที่ 2: ใช้ Secure Enclave (In-Place Encryption — แนะนำถ้าเป็นไปได้)

หากเปิดใช้ Secure Enclaves ไว้ (ดูบทที่ 9) สามารถทำ **in-place key rotation** ได้ โดยข้อมูลจะถูกถอดรหัสและเข้ารหัสใหม่ **ภายใน enclave บนตัวเซิร์ฟเวอร์เอง** โดยไม่ต้องส่งข้อมูลออกไปที่ client เลย ทำให้เร็วกว่ามากและลด network traffic:

```powershell
Set-SqlColumnEncryption -ColumnEncryptionSettings $cesc `
    -InputObject $database `
    -RolloverColumnEncryptionKey `
    -TargetEnclaveType "VBS"
```

## 8.4 แผนการหมุนเวียนคีย์สำหรับองค์กร (Rotation Runbook)

1. **แจ้งล่วงหน้า**: แจ้งทีมแอปพลิเคชันทั้งหมดที่เกี่ยวข้องอย่างน้อย 2-4 สัปดาห์ก่อนหมุนเวียน CEK (เพราะกระทบ downtime/performance)
2. **ทดสอบใน Staging**: รัน rotation script ใน staging ที่มีขนาดข้อมูลใกล้เคียง production เพื่อวัดเวลาที่ใช้จริง
3. **Backup ก่อนดำเนินการ**: สำรองฐานข้อมูลและคีย์เก่าก่อนเริ่ม
4. **CMK Rotation ก่อน CEK Rotation เสมอ**: ทำให้ระบบใช้ CMK ใหม่มั่นคงก่อน แล้วค่อยพิจารณา CEK rotation ซึ่งกระทบมากกว่า
5. **Monitor ระหว่างดำเนินการ**: จับตา error log ของแอปพลิเคชันและ SQL Server ระหว่าง rotation
6. **Grace Period**: คง CMK/CEK เก่าไว้ในช่วง grace period จนกว่าจะยืนยันว่าไม่มี client เหลือที่ใช้คีย์เก่า (ตรวจสอบผ่าน audit log ของ Key Vault)
7. **Cleanup**: ลบคีย์เก่าหลังพ้น grace period และปรับปรุงเอกสารบันทึกวันที่หมุนเวียนล่าสุด

## 8.5 กรณีฉุกเฉิน: คีย์รั่วไหล (Key Compromise)

หากสงสัยว่า CMK หรือ CEK รั่วไหล:

1. **เพิกถอนสิทธิ์เข้าถึงคีย์เดิมทันที** ผ่าน Key Vault access policy หรือ revoke certificate
2. หมุนเวียนทั้ง CMK และ CEK โดยเร็วที่สุด (ข้ามขั้นตอน grace period ปกติหากจำเป็นเพื่อความปลอดภัย แต่ยอมรับ downtime)
3. ตรวจสอบ audit log ว่ามีการเข้าถึง/ถอดรหัสข้อมูลผิดปกติในช่วงที่คีย์อาจรั่วไหลหรือไม่
4. แจ้ง incident response team และประเมินภาระการแจ้งเตือนผู้ได้รับผลกระทบตามข้อกฎหมาย (เช่น PDPA/GDPR breach notification)

# 9. Always Encrypted with Secure Enclaves

## 9.1 ปัญหาที่ Secure Enclaves แก้ไข

Always Encrypted แบบดั้งเดิม (บทที่ 1-8) มีข้อจำกัดสำคัญ: SQL Server ทำได้แค่เปรียบเทียบ **equality** บนคอลัมน์ deterministic เท่านั้น ไม่สามารถทำ:

- Range query (`>`, `<`, `BETWEEN`)
- Pattern matching (`LIKE`)
- Sorting (`ORDER BY`) บนคอลัมน์เข้ารหัส
- เปลี่ยนประเภทการเข้ารหัส หรือหมุนเวียนคีย์แบบ in-place โดยไม่ดึงข้อมูลออกไปที่ client

**Secure Enclaves** (เปิดตัวใน SQL Server 2019) แก้ปัญหานี้โดยสร้าง **พื้นที่หน่วยความจำที่ปลอดภัย (protected memory region)** บนตัวเซิร์ฟเวอร์เอง ที่แม้แต่ SQL Server engine, OS, หรือผู้ดูแลระบบก็ไม่สามารถมองเห็นข้อมูลภายในได้ — การคำนวณที่ต้องใช้ plaintext (เช่น range comparison) จะเกิดขึ้น**ภายใน enclave เท่านั้น** โดยข้อมูลจะถูกส่งเข้า-ออกจาก enclave ในรูปแบบเข้ารหัสเสมอ

## 9.2 ประเภทของ Enclave ที่รองรับ

| ประเภท | เทคโนโลยี | ใช้กับ |
|---|---|---|
| **VBS (Virtualization-based Security)** | Windows Hypervisor-based isolation | SQL Server 2019+ on Windows, Azure SQL Database/Managed Instance |
| **Intel SGX (Software Guard Extensions)** | Hardware-based isolation ผ่าน CPU | SQL Server 2019+ บนฮาร์ดแวร์ที่รองรับ SGX (ปัจจุบันมีใช้น้อยลงเพราะ Intel เลิกรองรับ SGX ใน consumer CPU รุ่นใหม่ — VBS เป็นทางเลือกหลักในปัจจุบัน) |

> สำหรับการติดตั้งใหม่ในปัจจุบัน แนะนำใช้ **VBS** เนื่องจากไม่ต้องพึ่งพาฮาร์ดแวร์เฉพาะทาง และ Microsoft สนับสนุนอย่างต่อเนื่องทั้งบน on-premises และ Azure

## 9.3 คุณสมบัติที่ Secure Enclave เปิดให้ใช้เพิ่มเติม

1. **Rich computations**: query แบบ `LIKE`, `>`, `<`, `BETWEEN`, `ORDER BY` บนข้อมูลเข้ารหัสได้ โดยใช้ randomized encryption ร่วมกับความสามารถของ enclave
2. **In-place encryption**: เข้ารหัสข้อมูลที่มีอยู่แล้วโดยไม่ต้องดึงออกมาที่ client ก่อน — เร็วกว่าและปลอดภัยกว่า (ข้อมูล plaintext ไม่ต้องเดินทางผ่านเครือข่าย)
3. **In-place key rotation**: หมุนเวียน CEK โดยถอดรหัส-เข้ารหัสใหม่ภายใน enclave (ดูบทที่ 8.3)
4. **Encryption type/algorithm change**: เปลี่ยนจาก randomized เป็น deterministic (หรือกลับกัน) โดยไม่ต้องดึงข้อมูลออกไปประมวลผลที่ client

## 9.4 การเปิดใช้งาน Secure Enclave (VBS) บน SQL Server

### 9.4.1 ข้อกำหนดของเครื่องเซิร์ฟเวอร์

- Windows Server 2019/2022 ที่เปิดใช้ **Hyper-V** และ **Virtualization-based Security**
- CPU รองรับ virtualization extensions (Intel VT-x/AMD-V) และ SLAT (Second Level Address Translation)
- SQL Server 2019+ Enterprise หรือ Developer Edition

### 9.4.2 เปิดใช้งานที่ระดับ SQL Server

```sql
-- ตรวจสอบว่า enclave attestation พร้อมใช้งานหรือไม่
sp_configure 'column encryption enclave type', 1;  -- 1 = VBS
RECONFIGURE;
```

จากนั้น restart SQL Server service

### 9.4.3 Attestation

ระบบต้องมีกลไก **Attestation** เพื่อยืนยันว่า enclave ที่กำลังทำงานเป็น enclave ที่ถูกต้องและไม่ถูกดัดแปลง ก่อนที่ client จะยอมส่งคีย์เข้าไปให้:

- **Host Guardian Service (HGS)**: สำหรับ on-premises VBS attestation
- **Azure Attestation**: สำหรับ Azure SQL Database/Managed Instance (จัดการให้อัตโนมัติโดย Microsoft)

Connection string ของ client ต้องระบุ attestation URL:

```
Server=myserver;Database=MyProdDb;Column Encryption Setting=Enabled;
Enclave Attestation Url=https://hgs.mycompany.local/Attestation;
Attestation Protocol=HGS;
```

## 9.5 การสร้าง CMK ที่รองรับ Enclave

เมื่อสร้าง CMK ผ่าน SSMS wizard หรือ T-SQL ต้องระบุว่า CMK นี้อนุญาตให้ enclave computation ได้:

```sql
CREATE COLUMN MASTER KEY [CMK_Enclave]
WITH (
    KEY_STORE_PROVIDER_NAME = N'AZURE_KEY_VAULT',
    KEY_PATH = N'https://mycompany-kv.vault.azure.net/keys/CMK-Enclave/...',
    ENCLAVE_COMPUTATIONS (SIGNATURE = 0x...)  -- ลายเซ็นที่ได้จากการรัน T-SQL/PowerShell สร้าง CMK ผ่าน wizard ที่เปิด "Enable computations on encrypted columns using secure enclaves"
);
```

ใน SSMS Wizard จะมี checkbox **"Enable computations on encrypted columns using secure enclaves"** ที่ต้องเลือกตั้งแต่ตอนสร้าง CMK

## 9.6 ตัวอย่าง Query ที่ทำได้เมื่อมี Enclave

```sql
-- Range query บนคอลัมน์เข้ารหัส (ทำไม่ได้ถ้าไม่มี enclave)
SELECT * FROM dbo.Employee
WHERE Salary BETWEEN @MinSalary AND @MaxSalary;

-- LIKE บนคอลัมน์เข้ารหัส
SELECT * FROM dbo.Customer
WHERE Email LIKE @SearchPattern;

-- ORDER BY บนคอลัมน์เข้ารหัส
SELECT * FROM dbo.Customer
ORDER BY LastName;  -- LastName เป็นคอลัมน์เข้ารหัสแบบ randomized + enclave
```

โดย query เหล่านี้ ตัว comparison logic จะถูกส่งเข้าไปคำนวณ**ภายใน enclave** — SQL Server engine ภายนอก enclave จะไม่เห็นทั้งค่า plaintext และผลเปรียบเทียบระดับ row เป็นค่า cleartext เลย

## 9.7 ข้อควรพิจารณา

- Secure Enclave ต้องการ Enterprise/Developer Edition (ไม่รองรับใน Standard Edition สำหรับ on-premises)
- Query ที่ใช้ enclave computation จะมี **performance overhead เพิ่มขึ้น** เมื่อเทียบกับ query ปกติ เพราะต้องส่งข้อมูลเข้า-ออก enclave — ควรทดสอบ benchmark ก่อนตัดสินใจใช้ในวงกว้าง
- ต้องวางแผนโครงสร้างพื้นฐานสำหรับ Attestation Service (HGS) เพิ่มเติมสำหรับ on-premises ซึ่งมีความซับซ้อนในการติดตั้งดูแล
- Azure SQL Database/Managed Instance ใช้งานง่ายกว่ามากเพราะ Azure จัดการ attestation ให้อัตโนมัติ — เป็นทางเลือกที่แนะนำสำหรับองค์กรที่ต้องการใช้ enclave แต่ไม่อยากดูแล infrastructure เพิ่ม
- ไม่ใช่ทุก data type และทุก operation ที่รองรับภายใน enclave — ตรวจสอบ compatibility matrix ล่าสุดจาก Microsoft Docs ก่อนออกแบบ schema

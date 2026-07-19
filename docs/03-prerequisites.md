# 3. ข้อกำหนดเบื้องต้นและการวางแผน

## 3.1 เวอร์ชันที่รองรับ

| ผลิตภัณฑ์ | เวอร์ชันขั้นต่ำ | หมายเหตุ |
|---|---|---|
| SQL Server (on-premises) | 2016 (13.x) ขึ้นไป | ทุก edition (รวม Standard/Express) รองรับ Always Encrypted พื้นฐาน |
| SQL Server (Secure Enclaves) | 2019 (15.x) ขึ้นไป | ต้องใช้ Enterprise/Developer edition และเปิด VBS หรือใช้ Intel SGX |
| Azure SQL Database | รองรับทุก tier | รองรับ Secure Enclaves (VBS) ด้วย |
| Azure SQL Managed Instance | รองรับ | รองรับ Secure Enclaves ด้วย |
| SSMS (SQL Server Management Studio) | 17.0 ขึ้นไป (แนะนำเวอร์ชันล่าสุด) | ใช้ wizard ตั้งค่าและจัดการคีย์ |
| .NET Framework / .NET | 4.6 ขึ้นไป / .NET Core 2.1+ (แนะนำ .NET 6+) | ผ่าน `Microsoft.Data.SqlClient` (แนะนำมากกว่า `System.Data.SqlClient` ที่เลิกพัฒนาแล้ว) |
| JDBC Driver | 6.0 ขึ้นไป | ต้องใช้ JRE 8+ และเปิด `columnEncryptionSetting=Enabled` |
| ODBC Driver | 17 ขึ้นไป | รองรับผ่าน connection string attribute |
| Python (pyodbc/pymssql) | pyodbc รองรับผ่าน ODBC Driver 17+ | pymssql ไม่รองรับ Always Encrypted โดยตรง |
| Node.js (Tedious/mssql) | รองรับตั้งแต่เวอร์ชันที่ระบุใน docs ของ driver | ตรวจสอบ compatibility matrix ก่อนใช้งานจริง |

## 3.2 การวางแผนก่อนติดตั้ง (Planning Checklist)

### 3.2.1 ระบุข้อมูลที่ต้องเข้ารหัส (Data Classification)

ก่อนเริ่มต้องทำ **data classification** เพื่อระบุว่าคอลัมน์ใดควรเข้ารหัส:

- ข้อมูลระบุตัวตน (PII): เลขบัตรประชาชน, หนังสือเดินทาง, วันเดือนปีเกิด
- ข้อมูลการเงิน: เลขบัตรเครดิต, เลขบัญชีธนาคาร
- ข้อมูลสุขภาพ (PHI): ประวัติการรักษา, ผลตรวจ
- ข้อมูล credential: รหัสผ่าน (แม้จะ hash แล้วก็ควรพิจารณา), API key, security question

> ใช้ SSMS's **Data Discovery & Classification** หรือ SQL Server's built-in classification เพื่อช่วยระบุคอลัมน์อ่อนไหวโดยอัตโนมัติได้

### 3.2.2 กำหนด Encryption Type ต่อคอลัมน์

สำหรับแต่ละคอลัมน์ที่จะเข้ารหัส ต้องตัดสินใจ:

- ใช้ **Deterministic** หรือ **Randomized** (ดูตารางเปรียบเทียบในบทที่ 2.5)
- คอลัมน์นี้จะถูกใช้ใน `WHERE`, `JOIN`, index หรือไม่? ถ้าใช่และไม่มี enclave → ต้องเป็น deterministic
- พิจารณาผลกระทบต่อ query pattern ที่มีอยู่เดิมทั้งหมด (stored procedure, view, report)

### 3.2.3 ตัดสินใจเรื่อง Secure Enclaves

ถ้าต้องการ:
- ค้นหาแบบ range (`>`, `<`, `BETWEEN`) หรือ `LIKE` บนคอลัมน์เข้ารหัส
- เปลี่ยนประเภทการเข้ารหัส หรือหมุนเวียนคีย์แบบ **in-place** (ไม่ต้องดึงข้อมูลออกมาถอดรหัส-เข้ารหัสใหม่ที่ฝั่ง client)

→ ต้องวางแผนใช้ **Secure Enclaves** ตั้งแต่ต้น (ต้องมี SQL Server 2019+ Enterprise/Developer และฮาร์ดแวร์ที่รองรับ VBS/SGX) ดูบทที่ [9](09-secure-enclaves.md)

### 3.2.4 ออกแบบกลยุทธ์การจัดการคีย์

- จะใช้ key store ใด (Certificate Store / Azure Key Vault / HSM)? — ดูบทที่ [7](07-key-management.md)
- ใครมีสิทธิ์เข้าถึง CMK บ้าง (แยกจากสิทธิ์ sysadmin ของ DBA)
- แผนสำรอง (backup) CMK และ escrow key เพื่อกู้คืนกรณีฉุกเฉิน
- แผนหมุนเวียนคีย์ตามรอบ compliance (เช่น ทุก 1 ปี) — ดูบทที่ [8](08-key-rotation.md)

### 3.2.5 ประเมินผลกระทบต่อแอปพลิเคชัน

- แอปพลิเคชันทั้งหมดที่ query คอลัมน์เหล่านี้ต้องอัปเดต driver และ connection string
- Query แบบ dynamic SQL หรือ ORM ที่ไม่ parameterize ค่าจะ **ใช้งานไม่ได้** กับคอลัมน์เข้ารหัส (ต้องแก้เป็น parameterized query)
- Reporting tools, BI tools, ETL/SSIS ที่เข้าถึงข้อมูลนี้โดยตรงต้องตรวจสอบว่ารองรับ Always Encrypted driver หรือไม่ (หลายเครื่องมือรุ่นเก่า **ไม่รองรับ**)
- ระบบ third-party integration (เช่น replication, CDC, full-text search) มีข้อจำกัดกับคอลัมน์เข้ารหัส — ดูบทที่ [10](10-limitations.md)

### 3.2.6 สิทธิ์ที่จำเป็น

| งาน | สิทธิ์ที่ต้องใช้ |
|---|---|
| สร้าง/จัดการ CMK, CEK metadata ในฐานข้อมูล | `ALTER ANY COLUMN MASTER KEY`, `ALTER ANY COLUMN ENCRYPTION KEY` |
| เข้ารหัส/ถอดรหัสคอลัมน์จริง (via wizard/PowerShell) | ต้องมีสิทธิ์เข้าถึง CMK ใน key store จริง (เช่น สิทธิ์บน Azure Key Vault) รวมถึง `ALTER TABLE`, `SELECT`, `UPDATE` บนตาราง |
| ให้แอปพลิเคชัน runtime ถอดรหัสข้อมูลได้ | สิทธิ์ "Get"/"Unwrap Key" บน Azure Key Vault หรือสิทธิ์อ่าน certificate private key บนเครื่อง client |

## 3.3 ข้อจำกัดชนิดข้อมูล (Data Type Restrictions)

คอลัมน์ที่จะเข้ารหัสมีข้อจำกัดชนิดข้อมูลบางประการ:

- ไม่รองรับ: `xml`, `text`, `ntext`, `image`, `rowversion`, `timestamp`, `sql_variant`, `geography`, `geometry`, hierarchyid, alias data types ที่อ้างอิงชนิดที่ไม่รองรับ
- คอลัมน์ที่เป็น deterministic encryption ต้องมี **collation ที่ลงท้ายด้วย `_BIN2`** (สำหรับชนิดข้อความ) เช่น `Latin1_General_BIN2`
- ไม่รองรับ computed column ที่อ้างอิงคอลัมน์เข้ารหัส (เว้นแต่ใช้ enclave บางกรณี)
- ไม่สามารถกำหนด `DEFAULT` constraint แบบปกติที่คำนวณค่าบนเซิร์ฟเวอร์ให้คอลัมน์เข้ารหัสได้ (ต้องส่งค่ามาแบบเข้ารหัสจาก client)

## 3.4 สภาพแวดล้อมทดสอบที่แนะนำ

ก่อนติดตั้งจริงใน production ควรมี:

1. **Dev environment**: ทดสอบ workflow การเข้ารหัส/ถอดรหัส และปรับโค้ดแอปพลิเคชัน
2. **Staging environment**: ทดสอบ performance, การหมุนเวียนคีย์, backup/restore
3. **แผน rollback**: เนื่องจากการเข้ารหัสคอลัมน์เป็น operation ที่ใช้เวลาและ lock ตาราง จึงควรมีแผนสำรองก่อนทำใน production เสมอ (ดูบทที่ 4-5)

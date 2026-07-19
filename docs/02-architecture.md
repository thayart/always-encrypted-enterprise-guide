# 2. สถาปัตยกรรมและองค์ประกอบหลัก

## 2.1 ภาพรวมสถาปัตยกรรม

```
┌─────────────────────────────────────────────────────────────────┐
│  Client Application                                              │
│                                                                    │
│   ┌───────────────┐        ┌────────────────────────────────┐   │
│   │  App Code     │──────▶│  Driver (ADO.NET / JDBC / ODBC)  │   │
│   └───────────────┘        │  - รู้ metadata ของ CMK/CEK       │   │
│                             │  - เข้ารหัส parameter ก่อนส่ง      │   │
│                             │  - ถอดรหัสผลลัพธ์ที่ได้รับกลับมา    │   │
│                             └───────────┬──────────────────────┘   │
│                                         │                          │
│                             ┌───────────▼──────────────┐          │
│                             │  Key Store Provider        │          │
│                             │  (Cert Store / Azure Key   │          │
│                             │   Vault / HSM / CSP)       │          │
│                             └────────────────────────────┘          │
└─────────────────────────────────────────────────────────────────┘
                                 │  (ciphertext เท่านั้น)
                                 ▼
┌─────────────────────────────────────────────────────────────────┐
│  SQL Server / Azure SQL Database                                  │
│   - เก็บ ciphertext ในคอลัมน์ที่เข้ารหัส                            │
│   - เก็บ metadata ของ CMK/CEK (แต่ไม่เก็บ CMK จริงและไม่เก็บ         │
│     CEK แบบ plaintext)                                             │
│   - ไม่สามารถถอดรหัสข้อมูลได้เอง (ยกเว้นกรณีใช้ Secure Enclave)      │
└─────────────────────────────────────────────────────────────────┘
```

## 2.2 Column Master Key (CMK)

CMK คือกุญแจ "แม่" ที่ใช้ปกป้อง (encrypt) CEK อีกทีหนึ่ง โดย CMK **ไม่ถูกเก็บไว้ใน SQL Server** เลย SQL Server รู้จักเพียง **metadata** ของ CMK เท่านั้น (ชื่อ, ตำแหน่งที่เก็บ, ประเภท key store) ซึ่งหมายความว่า:

- ผู้ที่ต้องการถอดรหัสข้อมูลจำเป็นต้องมีสิทธิ์เข้าถึง CMK ในที่จัดเก็บจริง (key store) เท่านั้น
- การขโมยฐานข้อมูล (ไฟล์ .mdf/.bak) เพียงอย่างเดียว **ไม่เพียงพอ** ต่อการถอดรหัสข้อมูล

### ที่จัดเก็บ CMK ที่รองรับ (Key Stores)

| Key Store | เหมาะกับ | หมายเหตุ |
|---|---|---|
| Windows Certificate Store (Current User / Local Machine) | Dev/Test, องค์กรขนาดเล็ก | จัดการง่าย แต่ต้อง export/import certificate ไปยังทุกเครื่องที่ต้องถอดรหัส |
| Azure Key Vault (AKV) | Production, Azure SQL, Hybrid | รองรับ RBAC, audit log, key rotation อัตโนมัติ, HSM-backed keys |
| Hardware Security Module (HSM) ผ่าน CNG provider | องค์กรที่ต้องการ FIPS 140-2/3 compliance | ต้องมี HSM on-premises (เช่น Thales, SafeNet) |
| Windows CSP/CNG Store | Custom PKI infrastructure | ใช้ร่วมกับ smart card หรือ enterprise CA |

> **ข้อแนะนำสำหรับองค์กร**: ใช้ Azure Key Vault หรือ HSM แทน Certificate Store แบบเปล่า เพราะรองรับการทำ audit, RBAC, การหมุนเวียนคีย์ และ high availability ได้ดีกว่ามาก ดูรายละเอียดในบทที่ [7. การบริหารจัดการคีย์](07-key-management.md)

## 2.3 Column Encryption Key (CEK)

CEK คือกุญแจที่ใช้เข้ารหัส/ถอดรหัสข้อมูลจริงในคอลัมน์ โดย:

- ค่าของ CEK (ทั้งแบบ plaintext และแบบเข้ารหัสแล้วด้วย CMK) จะถูกสร้างขึ้นครั้งเดียวตอน provisioning
- SQL Server เก็บเฉพาะ **CEK ที่ถูกเข้ารหัสด้วย CMK แล้ว** ไว้ใน system catalog (`sys.column_encryption_keys`)
- หนึ่งฐานข้อมูลสามารถมีหลาย CEK ได้ และหนึ่ง CEK สามารถถูกเข้ารหัสด้วยได้หลาย CMK (เพื่อรองรับ key rotation หรือหลาย key store)
- คอลัมน์ต่าง ๆ สามารถใช้ CEK ร่วมกันได้ หรือแยก CEK ตามความอ่อนไหวของข้อมูล (เช่น แยก CEK สำหรับ PII กับ Financial data)

## 2.4 Metadata Catalog Views ที่สำคัญ

```sql
-- ดูรายการ Column Master Keys
SELECT * FROM sys.column_master_keys;

-- ดูรายการ Column Encryption Keys
SELECT * FROM sys.column_encryption_keys;

-- ดูความสัมพันธ์ระหว่าง CEK กับ CMK ที่เข้ารหัสมัน
SELECT * FROM sys.column_encryption_key_values;

-- ดูคอลัมน์ที่ถูกเข้ารหัสในตาราง พร้อมประเภทการเข้ารหัส
SELECT
    t.name AS table_name,
    c.name AS column_name,
    c.encryption_type_desc,
    c.encryption_algorithm_name,
    cek.name AS cek_name
FROM sys.columns c
JOIN sys.tables t ON c.object_id = t.object_id
JOIN sys.column_encryption_keys cek ON c.column_encryption_key_id = cek.column_encryption_key_id
WHERE c.encryption_type IS NOT NULL;
```

## 2.5 Deterministic vs Randomized Encryption

| คุณสมบัติ | Deterministic | Randomized |
|---|---|---|
| ผลลัพธ์ ciphertext | เหมือนเดิมทุกครั้งสำหรับค่าเดียวกัน | สุ่มทุกครั้ง แม้ค่าเดิม |
| รองรับ `WHERE col = @val` | ได้ | ไม่ได้ (ต้องดึงข้อมูลมาถอดรหัสฝั่ง client ก่อน) |
| รองรับ `JOIN` บนคอลัมน์นี้ | ได้ | ไม่ได้ |
| รองรับ unique index / primary key | ได้ | ไม่ได้ |
| รองรับ `GROUP BY` / `DISTINCT` | ได้ | ไม่ได้ |
| รองรับ `LIKE`, `>`, `<`, `ORDER BY` | ไม่ได้ (ทั้งสองแบบ เว้นแต่ใช้ enclave) | ไม่ได้ |
| ความปลอดภัย | ต่ำกว่า (เสี่ยงต่อ frequency/pattern analysis หากข้อมูลมีค่าซ้ำน้อยแบบ เช่น เพศ, สถานะ) | สูงกว่า |
| การใช้งานแนะนำ | คอลัมน์ที่ใช้เป็นเงื่อนไขค้นหา/join เช่น เลขบัตรประชาชน, email | คอลัมน์ที่ไม่ต้องค้นหา เช่น เลขบัตรเครดิตเต็ม, หมายเหตุอ่อนไหว |

> หากต้องการทั้งค้นหาแบบ range/pattern **และ** ความปลอดภัยสูง ให้พิจารณาใช้ [Secure Enclaves](09-secure-enclaves.md) ซึ่งอนุญาตให้ทำ range query, `LIKE`, และการเข้ารหัสแบบ in-place ได้โดยไม่ลดทอนความปลอดภัยของ randomized encryption

## 2.6 Encryption Algorithm

Always Encrypted ใช้อัลกอริทึม `AEAD_AES_256_CBC_HMAC_SHA256` (Authenticated Encryption) ซึ่งให้ทั้งการเข้ารหัส (confidentiality) และการตรวจสอบความถูกต้อง (integrity) ของข้อมูล ป้องกันการปลอมแปลง ciphertext โดยไม่ถูกตรวจจับ

## 2.7 ลำดับการทำงานเมื่อรัน Query

1. แอปพลิเคชันส่ง query แบบมี parameter (parameterized query) ผ่าน driver
2. Driver ตรวจ metadata จาก SQL Server ว่าพารามิเตอร์ใดตรงกับคอลัมน์ที่เข้ารหัส (ผ่าน `sp_describe_parameter_encryption`)
3. Driver ดึง CMK จาก key store, ถอดรหัส CEK, แล้วใช้ CEK เข้ารหัสค่าพารามิเตอร์นั้น ๆ ก่อนส่งไปเป็น ciphertext
4. SQL Server รับ query ที่มี ciphertext อยู่แล้ว ประมวลผล (equality match กรณี deterministic) โดยไม่รู้ค่า plaintext
5. ผลลัพธ์ (ciphertext) ถูกส่งกลับมาที่ driver
6. Driver ถอดรหัสผลลัพธ์ด้วย CEK ก่อนส่งคืนให้แอปพลิเคชันเป็น plaintext

ขั้นตอนทั้งหมดนี้เกิดขึ้น**อัตโนมัติ**ที่ระดับ driver เมื่อเปิดใช้ `Column Encryption Setting=Enabled` ในค่าเชื่อมต่อ (connection string) — แอปพลิเคชันส่วนใหญ่แทบไม่ต้องแก้โค้ด logic เดิม นอกจากปรับการประกาศชนิดพารามิเตอร์ในบางกรณี (ดูบทที่ 6)

# 4. การติดตั้งผ่าน SSMS (Wizard)

วิธีนี้เหมาะสำหรับการติดตั้งครั้งแรก การทดสอบ หรือฐานข้อมูลขนาดเล็ก-กลาง เนื่องจากมี GUI นำทางเป็นขั้นตอน

## 4.1 ภาพรวมขั้นตอน

1. เชื่อมต่อฐานข้อมูลด้วย SSMS (เวอร์ชัน 17.0+)
2. เปิด **Always Encrypted Wizard**
3. เลือกคอลัมน์ที่จะเข้ารหัส และประเภทการเข้ารหัส (Deterministic/Randomized)
4. เลือกหรือสร้าง Column Master Key (CMK) และ Column Encryption Key (CEK)
5. เลือกวิธีรัน (ทันทีผ่าน wizard หรือ generate PowerShell script ไปรันภายหลัง)
6. ยืนยันและรัน

## 4.2 ขั้นตอนโดยละเอียด

### ขั้นที่ 1: เปิด Wizard

ใน Object Explorer: คลิกขวาที่ฐานข้อมูล → **Tasks** → **Encrypt Columns...**

### ขั้นที่ 2: หน้า Column Selection

- เลือกตารางและคอลัมน์ที่ต้องการเข้ารหัส
- สำหรับแต่ละคอลัมน์ เลือก **Encryption Type**:
  - `Deterministic` — สำหรับคอลัมน์ที่ต้องใช้ค้นหา/join
  - `Randomized` — สำหรับคอลัมน์ที่ไม่ต้องค้นหา ต้องการความปลอดภัยสูงสุด
- เลือก **Encryption Key** ที่จะใช้ (สร้างใหม่ หรือใช้ CEK ที่มีอยู่)

> **หมายเหตุ**: ถ้าคอลัมน์เป็น primary key, unique key, หรือใช้ใน index ต้องเลือก Deterministic เท่านั้น (Wizard จะแจ้งเตือนหากเลือกผิด)

### ขั้นที่ 3: หน้า Master Key Configuration

- **สร้าง CMK ใหม่** หรือ **เลือก CMK ที่มีอยู่แล้ว**
- เลือก Key Store:
  - `Windows Certificate Store — Current User`
  - `Windows Certificate Store — Local Machine`
  - `Azure Key Vault` (ต้อง sign-in ด้วยบัญชี Azure AD ที่มีสิทธิ์บน Key Vault นั้น)
  - `Hardware Security Module` (ถ้ามี CNG provider ติดตั้งอยู่)

ตัวอย่างการเลือก Azure Key Vault:
1. เลือก **Azure Key Vault**
2. คลิก **Sign In** และ login ด้วยบัญชีที่มีสิทธิ์ `Key Vault Crypto Officer` หรือ policy ที่อนุญาต create/get/wrapKey/unwrapKey
3. เลือก Subscription และ Key Vault ที่ต้องการ
4. เลือกใช้ key ที่มีอยู่ หรือสร้างใหม่

### ขั้นที่ 4: หน้า Run Settings

มีสองทางเลือก:

| ตัวเลือก | เหมาะกับ | รายละเอียด |
|---|---|---|
| **Proceed to finish now** | ฐานข้อมูลเล็ก, dev/test | Wizard จะเข้ารหัสข้อมูลทันที (ล็อกตารางระหว่างดำเนินการ) |
| **Generate PowerShell script to run later** | ฐานข้อมูลใหญ่, Production | ได้ .ps1 script ไปตรวจสอบและรันช่วง maintenance window เอง |

> **คำแนะนำสำหรับ Production**: เลือก **Generate PowerShell script** เสมอ เพื่อให้สามารถ review สคริปต์ก่อนรัน, รันนอกเวลาทำการ, และควบคุม transaction/locking ได้ดีกว่า

### ขั้นที่ 5: Summary และ Run

ตรวจสอบสรุปการตั้งค่าทั้งหมด (คอลัมน์, ประเภทเข้ารหัส, คีย์ที่ใช้) แล้วกด **Finish**

## 4.3 หลังการเข้ารหัสเสร็จสิ้น

ตรวจสอบผลลัพธ์ด้วย:

```sql
SELECT
    t.name AS table_name,
    c.name AS column_name,
    c.encryption_type_desc,
    c.encryption_algorithm_name
FROM sys.columns c
JOIN sys.tables t ON c.object_id = t.object_id
WHERE c.encryption_type IS NOT NULL
ORDER BY t.name, c.name;
```

จากนั้นต้องอัปเดต **connection string** ของทุกแอปพลิเคชันให้เปิดใช้ `Column Encryption Setting=Enabled` (ดูรายละเอียดในบทที่ 6) มิฉะนั้นแอปพลิเคชันจะเห็นเพียง ciphertext แบบ binary garbage แทนข้อมูลจริง

## 4.4 ข้อควรระวังเมื่อใช้ Wizard บนฐานข้อมูลขนาดใหญ่

- การเข้ารหัสคอลัมน์ที่มีข้อมูลอยู่แล้วเป็น operation ที่ **อ่าน-เขียนข้อมูลทุกแถวใหม่** (schema modification + data movement) จึงใช้เวลานานและ I/O สูงตามขนาดตาราง
- อาจเกิด table lock ระหว่างดำเนินการ ควรทำนอกเวลาทำการ (maintenance window)
- ควร backup ฐานข้อมูลก่อนดำเนินการเสมอ
- สำหรับตารางขนาดใหญ่มาก แนะนำใช้ **Online encryption** ผ่าน PowerShell (`-OnlineEncryption` flag) ที่ลดผลกระทบด้าน locking — ดูบทที่ 5

# 10. ข้อจำกัดและสิ่งที่ต้องพิจารณา

## 10.1 ข้อจำกัดด้าน Query

| การดำเนินการ | Deterministic | Randomized | Deterministic/Randomized + Enclave |
|---|---|---|---|
| `WHERE col = @val` (equality) | ได้ | ไม่ได้ | ได้ |
| `JOIN` บนคอลัมน์นี้ | ได้ | ไม่ได้ | ได้ |
| `GROUP BY` / `DISTINCT` | ได้ | ไม่ได้ | ได้ |
| `ORDER BY` | ไม่ได้ | ไม่ได้ | ได้ (ต้อง enclave) |
| `LIKE` | ไม่ได้ | ไม่ได้ | ได้ (ต้อง enclave) |
| `>`, `<`, `BETWEEN` | ไม่ได้ | ไม่ได้ | ได้ (ต้อง enclave) |
| ฟังก์ชันในตัว (`SUM`, `AVG`, string functions) | ไม่ได้ | ไม่ได้ | จำกัดมาก แม้มี enclave |
| Arithmetic operation บนคอลัมน์เข้ารหัส | ไม่ได้ | ไม่ได้ | ไม่ได้ (ยกเว้นบางกรณีเฉพาะ) |

## 10.2 ข้อจำกัดด้าน Schema/Object

- **View**: สามารถอ้างอิงคอลัมน์เข้ารหัสได้ แต่ query ผ่าน view ยังต้องเคารพข้อจำกัดด้าน query เช่นเดิม (equality เท่านั้นถ้าไม่มี enclave)
- **Computed Column**: ไม่สามารถสร้าง computed column ที่คำนวณจากคอลัมน์เข้ารหัสได้ในกรณีทั่วไป
- **Constraint**: ไม่รองรับ `CHECK` constraint หรือ `DEFAULT` constraint แบบคำนวณค่าบนเซิร์ฟเวอร์สำหรับคอลัมน์เข้ารหัส (default ต้องถูกส่งมาแบบเข้ารหัสจาก client)
- **Foreign Key**: รองรับได้ถ้าทั้งสองฝั่งเป็น deterministic encryption ด้วย CEK และ algorithm เดียวกัน
- **Full-Text Search**: ไม่รองรับคอลัมน์เข้ารหัส
- **Trigger**: Trigger ที่เข้าถึงคอลัมน์เข้ารหัสต้องระวังเรื่อง context การเชื่อมต่อที่ไม่มีสิทธิ์เข้าถึงคีย์ (trigger รันฝั่งเซิร์ฟเวอร์ ไม่มี client context)

## 10.3 ข้อจำกัดด้าน Replication/HA/DR

| ฟีเจอร์ | รองรับ Always Encrypted หรือไม่ |
|---|---|
| Backup/Restore | รองรับเต็มรูปแบบ (ciphertext ถูก backup ตามปกติ) |
| Always On Availability Groups | รองรับ |
| Log Shipping | รองรับ |
| Transactional Replication | รองรับบางส่วน — ต้องตรวจสอบ compatibility ของ subscriber |
| Change Data Capture (CDC) | รองรับตั้งแต่ SQL Server 2019+ (มีข้อจำกัดบางประการ) |
| Change Tracking | รองรับ |
| In-Memory OLTP (Memory-Optimized Tables) | รองรับตั้งแต่ SQL Server 2019+ แต่มีข้อจำกัดเพิ่มเติม |
| PolyBase | ไม่รองรับการ query คอลัมน์เข้ารหัสข้าม external table |

## 10.4 ข้อจำกัดด้าน Tools และ Ecosystem

- **SSRS (SQL Server Reporting Services)**: query ที่ดึงข้อมูลเข้ารหัสต้องใช้ connection string ที่เปิด `Column Encryption Setting=Enabled` และ dataset ต้องเป็น parameterized query — dynamic query builder ของ SSRS บางรุ่นอาจไม่รองรับ
- **Power BI**: การเชื่อมต่อ DirectQuery กับคอลัมน์เข้ารหัสมีข้อจำกัดมาก ควรพิจารณาสร้าง view/decrypted extract แยกสำหรับ BI ที่ผ่านกระบวนการ governance ที่เหมาะสม แทนที่จะ query ตรงเข้าคอลัมน์เข้ารหัส
- **SSIS (Integration Services)**: รองรับผ่าน ADO.NET connection manager ที่เปิด parameter encryption แต่ query builder แบบ visual อาจต้องปรับเป็น parameterized SQL เอง
- **Linked Servers**: การ query ข้าม linked server ไปยังตารางที่มีคอลัมน์เข้ารหัสมีข้อจำกัดมาก มักไม่แนะนำ
- **Third-party BI/ETL tools** ที่ไม่ได้ใช้ driver เวอร์ชันล่าสุดที่รองรับ Always Encrypted จะไม่สามารถถอดรหัสข้อมูลได้ (เห็นแต่ ciphertext)

## 10.5 ข้อจำกัดด้าน Performance/Operations

- ไม่มี index สนับสนุนการค้นหาแบบ range/pattern บนคอลัมน์เข้ารหัส (ยกเว้นใช้ enclave)
- Encrypted column ไม่สามารถใช้เป็น partition key ของ partitioned table ได้อย่างมีประสิทธิภาพ เนื่องจากไม่รองรับ range comparison
- Statistics/Cardinality estimation ของ query optimizer มีความแม่นยำจำกัดกว่าสำหรับคอลัมน์เข้ารหัส อาจทำให้ query plan ไม่เหมาะสมที่สุด

## 10.6 ข้อจำกัดด้านความปลอดภัยที่ต้องเข้าใจ (ไม่ใช่ Silver Bullet)

- Always Encrypted **ไม่ป้องกัน SQL Injection** ที่มุ่งเป้าคอลัมน์ที่ไม่ได้เข้ารหัส หรือการโจมตี business logic อื่น ๆ
- Deterministic encryption ยังเสี่ยงต่อ **frequency analysis** หากค่าที่เป็นไปได้มีจำนวนน้อย (เช่น เพศ, true/false) — ผู้โจมตีที่เห็น ciphertext จำนวนมากอาจอนุมานรูปแบบข้อมูลได้แม้ไม่รู้ค่าจริง
- หากแอปพลิเคชันที่มีสิทธิ์ถอดรหัสถูกบุกรุก (compromised) ผู้โจมตีจะเห็นข้อมูล plaintext ผ่านแอปพลิเคชันนั้นได้ — Always Encrypted ป้องกันการเข้าถึงผ่าน "ฐานข้อมูลโดยตรง" ไม่ใช่ป้องกันทุกช่องทาง
- ไม่ควรใช้ Always Encrypted แทนการควบคุมสิทธิ์ (authorization) หรือ network security — ควรใช้ร่วมกันแบบ defense-in-depth

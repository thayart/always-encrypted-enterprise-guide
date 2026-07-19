# 11. ผลกระทบด้านประสิทธิภาพ (Performance)

## 11.1 แหล่งที่มาของ Overhead

1. **CPU ที่ฝั่ง client**: การเข้ารหัส/ถอดรหัสทุก parameter และทุกแถวผลลัพธ์เกิดขึ้นที่ driver ฝั่งไคลเอนต์ ทำให้ใช้ CPU เพิ่มขึ้นบนแอปพลิเคชันเซิร์ฟเวอร์
2. **Round-trip เพิ่มเติม**: ครั้งแรกที่ driver รัน query ใหม่ (ที่ยังไม่มีใน cache) จะต้องเรียก `sp_describe_parameter_encryption` ก่อน เพื่อขอ metadata การเข้ารหัสของแต่ละพารามิเตอร์ — เพิ่ม round-trip หนึ่งครั้งต่อ query shape ที่ไม่ซ้ำกัน (แต่ถูก cache ไว้หลังจากนั้น)
3. **Key store latency**: การถอดรหัส CEK ต้องเรียก key store (เช่น Azure Key Vault) เพื่อ unwrap — หากไม่มี caching จะเพิ่ม network latency ทุกครั้ง
4. **ข้อจำกัดด้าน query plan**: คอลัมน์เข้ารหัสไม่มี useful statistics สำหรับ optimizer เท่าคอลัมน์ปกติ อาจทำให้เลือก execution plan ที่ไม่เหมาะสมที่สุด
5. **ไม่มี seekable range index**: query ที่ต้องเป็น range scan บนคอลัมน์เข้ารหัส (ไม่มี enclave) ต้องทำผ่าน table scan หรือดึงข้อมูลออกไปกรองที่ client

## 11.2 การลด Overhead ของ Metadata Round-trip

Driver ฝั่ง client จะ cache ผลลัพธ์ของ `sp_describe_parameter_encryption` ต่อ query shape ไว้ในหน่วยความจำ (in-process cache) ดังนั้น:

- แอปพลิเคชันที่รัน query ซ้ำ ๆ (เช่นผ่าน connection pool ระยะยาว) จะได้รับ overhead นี้เพียงครั้งแรกเท่านั้น
- แอปพลิเคชันที่สร้าง query แบบไดนามิกทุกครั้ง (query shape เปลี่ยนตลอด) จะเสีย overhead นี้ซ้ำ ๆ — ควรออกแบบให้ query shape คงที่มากที่สุดเท่าที่ทำได้ (parameterize แทนการเปลี่ยนโครงสร้าง query)
- Cache นี้จะถูกล้าง (invalidate) เมื่อมีการหมุนเวียนคีย์ — ควรวางแผน warm-up cache หลังทำ key rotation ใน production

## 11.3 การลด Overhead ของ Key Store Latency

- **Azure Key Vault**: driver บางตัวรองรับการ cache ผลลัพธ์การ unwrap CEK ไว้ในหน่วยความจำระยะสั้น เพื่อลดจำนวนครั้งที่ต้องเรียก Key Vault จริง — ตรวจสอบพฤติกรรม caching ของ driver เวอร์ชันที่ใช้
- ตั้งค่า Azure Key Vault ให้อยู่ใน region เดียวกับแอปพลิเคชันเซิร์ฟเวอร์เพื่อลด network latency
- พิจารณาใช้ Managed Identity (แทน client secret) ซึ่งมี caching token ในตัวอยู่แล้วโดย Azure SDK

## 11.4 แนวทางออกแบบเพื่อจำกัดผลกระทบ

1. **เข้ารหัสเฉพาะคอลัมน์ที่จำเป็นจริง ๆ**: อย่าเข้ารหัสทั้งตารางถ้าไม่จำเป็น เลือกเฉพาะคอลัมน์ที่มีข้อมูลอ่อนไหวตามผลจาก data classification (บทที่ 3)
2. **แยกตารางสำหรับข้อมูลอ่อนไหวสูง**: พิจารณาแยกคอลัมน์ที่ต้องเข้ารหัสไปไว้ในตารางเฉพาะ (คั่นด้วย 1:1 relationship) เพื่อไม่ให้ query ทั่วไปที่ไม่ต้องการข้อมูลอ่อนไหวต้องรับ overhead โดยไม่จำเป็น
3. **เลือก Deterministic เฉพาะที่จำเป็นต้องค้นหา/join**: ใช้ Randomized สำหรับคอลัมน์ที่ไม่ต้อง query โดยตรง เพื่อความปลอดภัยสูงสุดโดยไม่มีข้อดีด้าน query ที่ต้องแลก
4. **ทดสอบ Load Test ก่อนใช้งานจริง**: วัด throughput/latency ของ query pattern จริงทั้งก่อนและหลังเข้ารหัส เพื่อประเมินผลกระทบเชิงปริมาณและ capacity planning ที่แม่นยำ
5. **พิจารณา Secure Enclave หากต้อง query ซับซ้อน**: แม้ enclave จะมี overhead ของตัวเอง แต่อาจคุ้มกว่าการดึงข้อมูลจำนวนมากออกไปกรองที่ client
6. **Connection Pooling**: ใช้ connection pooling ให้เหมาะสมเพื่อลดจำนวนครั้งที่ driver ต้อง re-establish metadata cache ใหม่

## 11.5 ตัวชี้วัดที่ควร Monitor

| Metric | เหตุผล |
|---|---|
| Application-tier CPU utilization | จับ overhead จากการเข้ารหัส/ถอดรหัสที่ฝั่ง client |
| Query duration ก่อน-หลังเข้ารหัส (per query shape) | วัดผลกระทบจริงต่อ query pattern สำคัญ |
| Key Vault request latency/throttling | ตรวจจับ bottleneck จาก key store |
| Cache hit rate ของ parameter encryption metadata (ถ้า driver รายงานได้) | ประเมินประสิทธิภาพของ metadata caching |
| Lock wait time ระหว่าง encryption/rotation operation | ประเมินผลกระทบต่อ concurrent workload ระหว่างดำเนินการ maintenance |

## 11.6 ข้อสรุป

โดยทั่วไป overhead ของ Always Encrypted จะ**เห็นผลชัดที่ฝั่ง client/application tier มากกว่าฝั่งฐานข้อมูล** เนื่องจากงานเข้ารหัส/ถอดรหัสทั้งหมดเกิดที่ driver ไม่ใช่ที่ SQL Server engine ผลกระทบจะแตกต่างกันมากตามรูปแบบ workload — workload ที่ query ซ้ำรูปแบบเดิมบ่อย ๆ ผ่าน connection pool ที่มี metadata cache อุ่นอยู่แล้ว จะได้รับผลกระทบน้อยกว่า workload ที่สร้าง query แบบไดนามิกทุกครั้งอย่างมีนัยสำคัญ

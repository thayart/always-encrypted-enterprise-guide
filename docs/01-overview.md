# 1. ภาพรวมและแนวคิดพื้นฐาน

## 1.1 Always Encrypted คืออะไร

Always Encrypted เป็นเทคโนโลยีเข้ารหัสข้อมูลที่ทำงานร่วมกันระหว่าง **SQL Server/Azure SQL Database** และ **ไดรเวอร์ฝั่งไคลเอนต์** (เช่น ADO.NET, JDBC, ODBC, Node.js driver) โดยมีหลักการสำคัญคือ:

- ข้อมูลถูกเข้ารหัส (encrypt) และถอดรหัส (decrypt) **ที่ฝั่งไคลเอนต์เท่านั้น**
- คีย์เข้ารหัส (encryption keys) ไม่เคยถูกส่งไปยัง SQL Server หรือจัดเก็บบนเซิร์ฟเวอร์ฐานข้อมูล
- SQL Server เห็นเพียง **ciphertext** (ข้อมูลที่เข้ารหัสแล้ว) เท่านั้น ไม่ว่าจะเป็นตอน query, backup, หรือ replication

ผลลัพธ์คือ การแบ่งแยกหน้าที่ (separation of duties) ระหว่างผู้ที่ดูแลฐานข้อมูล (DBA) กับผู้ที่เป็นเจ้าของข้อมูล (data owner) อย่างชัดเจน — DBA สามารถบริหารจัดการเซิร์ฟเวอร์ได้โดยไม่จำเป็นต้องเห็นข้อมูลอ่อนไหว

## 1.2 ปัญหาที่ Always Encrypted แก้ไข

การเข้ารหัสแบบเดิม เช่น **Transparent Data Encryption (TDE)** จะเข้ารหัสข้อมูล "at rest" (ไฟล์ฐานข้อมูลบนดิสก์) แต่เมื่อข้อมูลถูกอ่านขึ้นมาประมวลผลในหน่วยความจำของ SQL Server ข้อมูลจะกลับเป็น plaintext ทันที ทำให้:

- ผู้ที่มีสิทธิ์ sysadmin หรือเข้าถึงเซิร์ฟเวอร์โดยตรงยังเห็นข้อมูลจริงได้
- ข้อมูลที่ไหลผ่านเครือข่าย (in transit) ต้องพึ่งพา TLS/SSL แยกต่างหาก
- Memory dump หรือ backup ที่ถูกขโมยอาจถูกใช้เพื่อกู้คืนข้อมูลได้หากไม่มีการป้องกันเพิ่มเติม

Always Encrypted แก้ปัญหานี้โดยทำให้ข้อมูลอยู่ในรูปแบบเข้ารหัส **ตลอดวงจรชีวิต**: ตั้งแต่อยู่ในดิสก์ (at rest), ในหน่วยความจำ (in use บนฝั่งเซิร์ฟเวอร์), ระหว่างการส่งผ่านเครือข่าย (in transit), ไปจนถึงใน backup — และจะถูกถอดรหัสก็ต่อเมื่อถึงฝั่งแอปพลิเคชันที่มีสิทธิ์เข้าถึงคีย์เท่านั้น

## 1.3 เปรียบเทียบกับเทคนิคเข้ารหัสอื่นของ SQL Server

| เทคนิค | เข้ารหัสที่ไหน | ป้องกัน DBA เห็นข้อมูล | ระดับ |
|---|---|---|---|
| TDE (Transparent Data Encryption) | ไฟล์ฐานข้อมูล/log/backup (at rest) | ไม่ | Database/Server |
| Cell-level Encryption (`ENCRYPTBYKEY`) | คอลัมน์ (ต้องเขียนโค้ดเรียกฟังก์ชันเอง) | บางส่วน (ต้องจัดการ key เอง) | Column |
| Always Encrypted | คอลัมน์ ตั้งแต่ฝั่งไคลเอนต์ (client-side) | ใช่ | Column |
| Always Encrypted with Secure Enclaves | คอลัมน์ + คำนวณได้ใน enclave ฝั่งเซิร์ฟเวอร์อย่างปลอดภัย | ใช่ (ยกเว้นภายใน enclave) | Column |
| Transport Layer Security (TLS) | ระหว่างทาง (in transit) | ไม่เกี่ยวข้องกับ at rest/in use | Network |

Always Encrypted ควรใช้ **ร่วมกับ** TDE และ TLS ไม่ใช่ใช้แทนกัน เพื่อให้ครอบคลุมทุกสถานะของข้อมูล (defense in depth)

## 1.4 แนวคิดหลักที่ต้องเข้าใจก่อนเริ่ม

- **Column Master Key (CMK)**: กุญแจหลักที่เก็บอยู่นอก SQL Server (เช่น Windows Certificate Store, Azure Key Vault, HSM) ใช้ปกป้อง CEK
- **Column Encryption Key (CEK)**: กุญแจที่ใช้เข้ารหัสข้อมูลในคอลัมน์จริง ๆ โดยตัว CEK เองก็ถูกเข้ารหัสด้วย CMK อีกชั้นหนึ่งก่อนถูกเก็บใน metadata ของฐานข้อมูล
- **Deterministic Encryption**: การเข้ารหัสที่ให้ผลลัพธ์ ciphertext เดิมทุกครั้งสำหรับ plaintext เดียวกัน รองรับการค้นหาแบบ equality (`WHERE`, `JOIN`, `GROUP BY`, unique index) แต่มีความเสี่ยงเรื่อง pattern analysis มากกว่า
- **Randomized Encryption**: การเข้ารหัสที่ให้ผลลัพธ์ต่างกันทุกครั้งแม้ plaintext เดียวกัน ปลอดภัยกว่าแต่ไม่รองรับการค้นหา/เรียงลำดับ/join บนคอลัมน์นั้น
- **Enclave-enabled Always Encrypted**: ส่วนขยายที่อนุญาตให้ทำการคำนวณ (pattern matching, range comparison, in-place encryption) บนข้อมูลเข้ารหัสได้ภายใน secure enclave ที่ทำงานบนตัวเซิร์ฟเวอร์เอง โดยยังคงความปลอดภัยไว้

## 1.5 กรณีใช้งานทั่วไป

- ระบบที่ต้องปฏิบัติตามมาตรฐาน PCI-DSS (เก็บเลขบัตรเครดิต), HIPAA (ข้อมูลสุขภาพ), PDPA/GDPR (ข้อมูลส่วนบุคคล)
- ระบบ SaaS แบบ multi-tenant ที่ผู้ให้บริการ cloud ต้อง "เห็นน้อยที่สุด" (least privilege / zero trust ต่อผู้ดูแลระบบ)
- องค์กรที่ใช้ DBA ภายนอก (outsourced/managed DBA) แต่ไม่ต้องการให้เห็นข้อมูลลูกค้า
- การย้ายฐานข้อมูลไปยัง Azure SQL Database โดยยังต้องการควบคุมคีย์เข้ารหัสเอง (customer-managed keys)

ในบทถัดไปจะอธิบายสถาปัตยกรรมและองค์ประกอบเหล่านี้อย่างละเอียด

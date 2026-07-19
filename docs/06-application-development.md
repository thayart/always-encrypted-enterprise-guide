# 6. การพัฒนาแอปพลิเคชันให้รองรับ Always Encrypted

## 6.1 หลักการทั่วไป

การใช้งาน Always Encrypted จากฝั่งแอปพลิเคชันต้องอาศัย driver ที่รองรับ (ดูตารางเวอร์ชันในบทที่ 3) และเปิดใช้งานผ่าน connection string หรือ configuration ของ driver นั้น ๆ โดยหลักการสำคัญที่ต้องจำ:

- **ต้องใช้ parameterized query เท่านั้น** — Dynamic SQL ที่ต่อ string ค่าเข้าไปตรง ๆ จะใช้งานไม่ได้กับคอลัมน์เข้ารหัส
- ต้องระบุชนิดข้อมูล (data type) และขนาดของพารามิเตอร์ให้ตรงกับคอลัมน์ในฐานข้อมูล มิฉะนั้นจะเกิด error หรือพฤติกรรมผิดพลาด
- Driver จะ cache metadata การเข้ารหัสไว้ (เพื่อลด round-trip ไปถาม `sp_describe_parameter_encryption`) ควรทำความเข้าใจ cache invalidation เมื่อมีการหมุนเวียนคีย์

## 6.2 .NET (ADO.NET) ผ่าน Microsoft.Data.SqlClient

> แนะนำให้ใช้ `Microsoft.Data.SqlClient` แทน `System.Data.SqlClient` รุ่นเก่า เพราะรองรับฟีเจอร์ใหม่ (เช่น Always Encrypted with Secure Enclaves, Azure AD auth) และยังได้รับการพัฒนาต่อเนื่อง

### Connection String

```
Server=myserver.database.windows.net;
Database=MyProdDb;
Column Encryption Setting=Enabled;
Authentication=Active Directory Integrated;
```

### ตัวอย่างโค้ด C#

```csharp
using Microsoft.Data.SqlClient;

var connString = "Server=myserver;Database=MyProdDb;Column Encryption Setting=Enabled;Integrated Security=True;";

using var connection = new SqlConnection(connString);
connection.Open();

using var command = new SqlCommand(
    "SELECT CustomerId, FullName FROM dbo.Customer WHERE NationalId = @NationalId", connection);

// ต้องระบุชนิดข้อมูลและขนาดให้ตรงกับคอลัมน์ในฐานข้อมูลเป๊ะ ๆ
command.Parameters.Add("@NationalId", System.Data.SqlDbType.Char, 13).Value = "1234567890123";

using var reader = command.ExecuteReader();
while (reader.Read())
{
    Console.WriteLine($"{reader["CustomerId"]}: {reader["FullName"]}");
}
```

### การกำหนด Key Store Provider แบบ Custom (เช่น Azure Key Vault)

```csharp
using Microsoft.Data.SqlClient;
using Azure.Identity;

var credential = new DefaultAzureCredential();
var akvProvider = new SqlColumnEncryptionAzureKeyVaultProvider(credential);

SqlConnection.RegisterColumnEncryptionKeyStoreProviders(
    new Dictionary<string, SqlColumnEncryptionKeyStoreProvider>
    {
        { SqlColumnEncryptionAzureKeyVaultProvider.ProviderName, akvProvider }
    });
```

> สำหรับ Windows Certificate Store ไม่จำเป็นต้อง register provider เองเพราะเป็นค่า default ของ driver อยู่แล้ว

## 6.3 JDBC (Java)

### Connection URL

```
jdbc:sqlserver://myserver.database.windows.net:1433;
database=MyProdDb;
columnEncryptionSetting=Enabled;
keyVaultProviderClientId=<app-client-id>;
keyVaultProviderClientKey=<app-client-secret>;
```

### ตัวอย่างโค้ด Java

```java
String url = "jdbc:sqlserver://myserver;database=MyProdDb;columnEncryptionSetting=Enabled;";
try (Connection conn = DriverManager.getConnection(url, "user", "password");
     PreparedStatement pstmt = conn.prepareStatement(
         "SELECT CustomerId, FullName FROM dbo.Customer WHERE NationalId = ?")) {

    pstmt.setString(1, "1234567890123");
    try (ResultSet rs = pstmt.executeQuery()) {
        while (rs.next()) {
            System.out.println(rs.getInt("CustomerId") + ": " + rs.getString("FullName"));
        }
    }
}
```

## 6.4 ODBC / OLE DB (C++, Python ผ่าน pyodbc)

### Connection String

```
Driver={ODBC Driver 17 for SQL Server};
Server=myserver;
Database=MyProdDb;
ColumnEncryption=Enabled;
```

### ตัวอย่าง Python (pyodbc)

```python
import pyodbc

conn_str = (
    "Driver={ODBC Driver 17 for SQL Server};"
    "Server=myserver;Database=MyProdDb;"
    "ColumnEncryption=Enabled;"
    "Trusted_Connection=yes;"
)

conn = pyodbc.connect(conn_str)
cursor = conn.cursor()
cursor.execute(
    "SELECT CustomerId, FullName FROM dbo.Customer WHERE NationalId = ?",
    ("1234567890123",)
)
for row in cursor.fetchall():
    print(row.CustomerId, row.FullName)
```

## 6.5 ข้อควรระวังในการพัฒนา

### 6.5.1 Dynamic SQL ใช้ไม่ได้

```sql
-- ผิด: ต่อ string ตรง ๆ จะไม่สามารถถอดรหัสให้ได้
SET @sql = 'SELECT * FROM Customer WHERE NationalId = ''' + @nid + ''''
EXEC(@sql)
```

ต้องใช้ `sp_executesql` พร้อม parameter แทน:

```sql
EXEC sp_executesql
    N'SELECT * FROM Customer WHERE NationalId = @nid',
    N'@nid CHAR(13)',
    @nid = @NationalIdValue;
```

### 6.5.2 ORM Frameworks

- **Entity Framework Core**: รองรับผ่าน `Microsoft.Data.SqlClient` ตั้งแต่เปิด `Column Encryption Setting=Enabled` ใน connection string — โดยทั่วไปใช้งานได้ทันทีถ้า query เป็น parameterized (LINQ ส่วนใหญ่แปลงเป็น parameterized query อยู่แล้ว)
- **Dapper**: ใช้งานได้ตามปกติเพราะ Dapper ใช้ parameterized query เป็นค่าเริ่มต้น
- **Hibernate (Java)**: ต้องตรวจสอบว่า JDBC connection ที่ Hibernate ใช้เปิด `columnEncryptionSetting=Enabled` และ HQL/Criteria query ที่ generate ออกมาเป็น PreparedStatement (ปกติเป็นอยู่แล้ว)
- ระวัง ORM ที่มี query cache หรือ SQL translation layer บางตัวที่อาจ inline literal value แทน parameter ในบางกรณี (เช่น batch insert บางรูปแบบ) — ต้องทดสอบเจาะจง

### 6.5.3 การเปรียบเทียบชนิดข้อมูลกับพารามิเตอร์ (Type Mismatch)

Driver จะปฏิเสธ query หากชนิด/ขนาดของพารามิเตอร์ไม่ตรงกับคอลัมน์ เช่น ส่ง `NVARCHAR` ไปเทียบกับคอลัมน์ที่เป็น `VARCHAR` encrypted จะเกิด error `Operand type clash` ดังนั้นควร:

- Mapping data type ให้ตรงเป๊ะระหว่างแอปกับ schema
- ระวังเรื่อง implicit conversion ที่เคยทำงานได้ในคอลัมน์ปกติ แต่จะ error ทันทีเมื่อเป็นคอลัมน์เข้ารหัส

### 6.5.4 Stored Procedures

Stored procedure ที่ใช้พารามิเตอร์กับคอลัมน์เข้ารหัสยังใช้งานได้ปกติ ตราบใดที่ driver เรียกผ่าน parameterized call (เช่น `SqlCommand.CommandType = StoredProcedure`) และพารามิเตอร์ของ SP ตรงชนิดกับคอลัมน์

### 6.5.5 Error Handling ที่พบบ่อย

| Error | สาเหตุ | วิธีแก้ |
|---|---|---|
| `Operand type clash` | ชนิด/ขนาดพารามิเตอร์ไม่ตรงกับคอลัมน์ | ตรวจสอบ data type mapping |
| `Encryption scheme mismatch` | ใช้ query แบบ range/LIKE บนคอลัมน์ deterministic/randomized (ไม่มี enclave) | เปลี่ยน query pattern หรือใช้ Secure Enclave |
| `Failed to decrypt column encryption key` | ไม่มีสิทธิ์เข้าถึง CMK ใน key store จากเครื่อง client นั้น | ตรวจสอบสิทธิ์ certificate/Key Vault access policy |
| ผลลัพธ์เป็น binary garbage แทนค่าจริง | ลืมเปิด `Column Encryption Setting=Enabled` ใน connection string | แก้ connection string |

## 6.6 ตัวอย่าง JOIN บนคอลัมน์เข้ารหัส

SQL Server เปรียบเทียบ ciphertext ของสองคอลัมน์กันตรง ๆ ได้ (โดยไม่ต้องถอดรหัส) **ก็ต่อเมื่อทั้งสองคอลัมน์เข้ากันได้ทุกข้อต่อไปนี้**:

- ทั้งคู่เป็น **Deterministic encryption** (Randomized join กันไม่ได้เลย แม้จะ join กับตัวเอง)
- ใช้ **Column Encryption Key (CEK) เดียวกัน**
- ใช้ **algorithm เดียวกัน** (`AEAD_AES_256_CBC_HMAC_SHA256`)
- ชนิดข้อมูลและ collation ตรงกัน (`_BIN2`)

> เมื่อเข้าเงื่อนไขครบ การ join จะเกิดขึ้น **ทั้งหมดที่ฝั่งเซิร์ฟเวอร์** โดยไม่ต้องส่งค่า plaintext ผ่าน driver เลย — driver มีหน้าที่แค่ถอดรหัสคอลัมน์ผลลัพธ์ตอนส่งกลับเท่านั้น

### ตัวอย่าง Schema

```sql
CREATE TABLE dbo.Customer (
    CustomerId    INT IDENTITY PRIMARY KEY,          -- ไม่เข้ารหัส
    NationalId    CHAR(13) COLLATE Latin1_General_BIN2
        ENCRYPTED WITH (
            COLUMN_ENCRYPTION_KEY = [CEK_Prod],
            ENCRYPTION_TYPE = DETERMINISTIC,
            ALGORITHM = 'AEAD_AES_256_CBC_HMAC_SHA256'
        ) NOT NULL,
    FullName      NVARCHAR(100) NOT NULL              -- ไม่เข้ารหัส
);

CREATE TABLE dbo.Orders (
    OrderId            INT IDENTITY PRIMARY KEY,      -- ไม่เข้ารหัส
    CustomerNationalId CHAR(13) COLLATE Latin1_General_BIN2
        ENCRYPTED WITH (
            COLUMN_ENCRYPTION_KEY = [CEK_Prod],        -- ใช้ CEK เดียวกับ Customer.NationalId
            ENCRYPTION_TYPE = DETERMINISTIC,
            ALGORITHM = 'AEAD_AES_256_CBC_HMAC_SHA256'
        ) NOT NULL,
    OrderDate DATETIME2 NOT NULL                       -- ไม่เข้ารหัส
);
```

### T-SQL

```sql
SELECT o.OrderId, o.OrderDate, c.FullName
FROM dbo.Orders o
JOIN dbo.Customer c ON o.CustomerNationalId = c.NationalId
WHERE o.OrderDate >= @StartDate;   -- OrderDate ไม่เข้ารหัส เปรียบเทียบแบบ range ได้ตามปกติ
```

query นี้ไม่ต้องมีพารามิเตอร์พิเศษสำหรับ `CustomerNationalId`/`NationalId` เลย เพราะทั้งสองคอลัมน์เก็บ ciphertext ที่เปรียบเทียบกันได้อยู่แล้วในตัวมันเอง

### C# (ผ่าน Stored Procedure)

```csharp
using var command = new SqlCommand(@"
    SELECT o.OrderId, o.OrderDate, c.FullName
    FROM dbo.Orders o
    JOIN dbo.Customer c ON o.CustomerNationalId = c.NationalId
    WHERE o.OrderDate >= @StartDate", connection);

command.Parameters.Add("@StartDate", System.Data.SqlDbType.DateTime2).Value = DateTime.Today.AddDays(-30);

using var reader = command.ExecuteReader();
while (reader.Read())
{
    Console.WriteLine($"{reader["OrderId"]}: {reader["FullName"]} on {reader["OrderDate"]}");
}
```

### เมื่อ JOIN ไม่สำเร็จ

หาก `CEK` หรือ algorithm ของสองคอลัมน์ไม่ตรงกัน (เช่น สร้าง `Orders.CustomerNationalId` ด้วย `CEK_Orders` คนละตัวกับ `Customer.NationalId` ที่ใช้ `CEK_Prod`) จะได้ error:

```
Msg 33299, Level 16, State 3
Encryption scheme mismatch for columns/variables 'CustomerNationalId', 'NationalId'.
The encryption scheme for the columns/variables is (encryption_type = 'DETERMINISTIC',
encryption_algorithm_name = 'AEAD_AES_256_CBC_HMAC_SHA256', column_encryption_key_name = 'CEK_Orders' ...)
which is incompatible with the scheme used by the other columns/variables (... column_encryption_key_name = 'CEK_Prod' ...)
```

**วิธีแก้**: วางแผนตั้งแต่ตอนออกแบบ schema ให้คอลัมน์ที่ต้อง join กัน **ใช้ CEK เดียวกัน** เสมอ (ดูบทที่ [3.2.2](03-prerequisites.md#322-กำหนด-encryption-type-ต่อคอลัมน์)) — หากคอลัมน์ถูกสร้างแยก CEK กันไปแล้ว ต้องทำ [CEK rotation](08-key-rotation.md) เพื่อรวมให้ใช้ CEK เดียวกันก่อนจึง join ได้

## 6.7 ตัวอย่าง INSERT

INSERT ทำงานเหมือนคอลัมน์ปกติทุกประการ ตราบใดที่ค่าที่ส่งเข้าคอลัมน์เข้ารหัสมาจาก **parameter** ไม่ใช่ literal string ที่ต่อเข้า query ตรง ๆ

### C#

```csharp
using var command = new SqlCommand(@"
    INSERT INTO dbo.Customer (NationalId, CreditCardNumber, FullName)
    VALUES (@NationalId, @CreditCardNumber, @FullName)", connection);

command.Parameters.Add("@NationalId", System.Data.SqlDbType.Char, 13).Value = "1234567890123";
command.Parameters.Add("@CreditCardNumber", System.Data.SqlDbType.VarChar, 19).Value = "4111111111111111";
command.Parameters.Add("@FullName", System.Data.SqlDbType.NVarChar, 100).Value = "สมชาย ใจดี"; // ไม่เข้ารหัส

command.ExecuteNonQuery();
```

### ผ่าน Stored Procedure (แนะนำสำหรับ Production)

```sql
CREATE PROCEDURE dbo.usp_InsertCustomer
    @NationalId       CHAR(13),
    @CreditCardNumber VARCHAR(19),
    @FullName         NVARCHAR(100)
AS
BEGIN
    INSERT INTO dbo.Customer (NationalId, CreditCardNumber, FullName)
    VALUES (@NationalId, @CreditCardNumber, @FullName);
END
```

```csharp
using var command = new SqlCommand("dbo.usp_InsertCustomer", connection)
{
    CommandType = System.Data.CommandType.StoredProcedure
};
command.Parameters.Add("@NationalId", System.Data.SqlDbType.Char, 13).Value = "1234567890123";
command.Parameters.Add("@CreditCardNumber", System.Data.SqlDbType.VarChar, 19).Value = "4111111111111111";
command.Parameters.Add("@FullName", System.Data.SqlDbType.NVarChar, 100).Value = "สมชาย ใจดี";
command.ExecuteNonQuery();
```

### ทดสอบผ่าน SSMS ด้วย T-SQL ตรง ๆ

การเขียน INSERT แบบใส่ literal ตรง ๆ **ใช้ไม่ได้** แม้จะเปิด "Always Encrypted" ใน connection properties ของ SSMS แล้วก็ตาม เพราะ literal ไม่ถูกส่งเป็น parameter:

```sql
-- ผิด: literal ตรง ๆ ไม่ถูกเข้ารหัสให้ แม้เปิด Always Encrypted ใน SSMS
INSERT INTO dbo.Customer (NationalId, FullName) VALUES ('1234567890123', N'ทดสอบ');
-- Msg 206: Operand type clash: varchar is incompatible with the encrypted column ...
```

```sql
-- ถูก: ประกาศเป็นตัวแปรก่อน — SSMS จะ parameterize ตัวแปรให้อัตโนมัติเมื่อเปิด "Always Encrypted"
DECLARE @NationalId CHAR(13) = '1234567890123';
INSERT INTO dbo.Customer (NationalId, FullName) VALUES (@NationalId, N'ทดสอบ');
```

> เปิดใช้งานได้ที่ **SSMS → Query → Query Options → Advanced → Enable Parameterization for Always Encrypted** และ Connection Properties ต้องเปิด **Always Encrypted** ด้วย

### ข้อจำกัดของ Bulk Insert / Table-Valued Parameters

- `SqlBulkCopy` รองรับ Always Encrypted ได้ (ตั้งแต่ driver เวอร์ชันที่รองรับ) แต่ต้องเปิด `Column Encryption Setting=Enabled` ในทั้ง source และ destination connection และค่าที่ส่งเข้าต้องเป็น plaintext ให้ driver เข้ารหัสให้อัตโนมัติ (ห้ามตั้ง `AllowEncryptedValueModifications`)
- Table-Valued Parameters (TVP) ที่มีคอลัมน์ตรงกับคอลัมน์เข้ารหัสมีข้อจำกัดในหลาย driver เวอร์ชัน — ควรทดสอบเจาะจงหรือใช้ loop แบบ parameterized insert ทีละแถวแทนหากพบปัญหา

## 6.8 ตัวอย่าง UPDATE

### กรณีที่ 1: filter ด้วยคอลัมน์ที่ไม่เข้ารหัส (แนะนำที่สุด)

```csharp
using var command = new SqlCommand(@"
    UPDATE dbo.Customer
    SET CreditCardNumber = @NewCreditCardNumber
    WHERE CustomerId = @CustomerId", connection);   // CustomerId เป็น PK ไม่เข้ารหัส

command.Parameters.Add("@NewCreditCardNumber", System.Data.SqlDbType.VarChar, 19).Value = "4222222222222";
command.Parameters.Add("@CustomerId", System.Data.SqlDbType.Int).Value = 42;

command.ExecuteNonQuery();
```

### กรณีที่ 2: filter ด้วยคอลัมน์เข้ารหัสแบบ Deterministic

คอลัมน์ deterministic รองรับ equality ใน `WHERE` ได้ตามปกติ — driver จะเข้ารหัสค่าพารามิเตอร์ทั้งสองตัว (ค่าที่ใช้กรองและค่าที่จะ set) ให้เองโดยอัตโนมัติ:

```sql
UPDATE dbo.Customer
SET FullName = @NewFullName
WHERE NationalId = @NationalId;
```

```csharp
using var command = new SqlCommand(@"
    UPDATE dbo.Customer SET FullName = @NewFullName WHERE NationalId = @NationalId", connection);

command.Parameters.Add("@NewFullName", System.Data.SqlDbType.NVarChar, 100).Value = "สมหญิง ใจดี";
command.Parameters.Add("@NationalId", System.Data.SqlDbType.Char, 13).Value = "1234567890123";

command.ExecuteNonQuery();
```

### กรณีที่ 3: ห้าม filter ด้วยคอลัมน์เข้ารหัสแบบ Randomized

`WHERE CreditCardNumber = @CreditCardNumber` จะ **error ทันที** ถ้า `CreditCardNumber` เป็น Randomized encryption (ไม่รองรับ equality) วิธีแก้คือต้องหา primary key ผ่านคอลัมน์อื่นที่ query ได้ก่อน แล้วค่อย update ด้วย PK นั้น:

```csharp
// ขั้นที่ 1: หา CustomerId จากคอลัมน์ deterministic หรือคอลัมน์ที่ไม่เข้ารหัส
int customerId;
using (var lookup = new SqlCommand(
    "SELECT CustomerId FROM dbo.Customer WHERE NationalId = @NationalId", connection))
{
    lookup.Parameters.Add("@NationalId", System.Data.SqlDbType.Char, 13).Value = "1234567890123";
    customerId = (int)lookup.ExecuteScalar();
}

// ขั้นที่ 2: update ด้วย PK แทน
using var update = new SqlCommand(
    "UPDATE dbo.Customer SET CreditCardNumber = @NewCard WHERE CustomerId = @CustomerId", connection);
update.Parameters.Add("@NewCard", System.Data.SqlDbType.VarChar, 19).Value = "4222222222222";
update.Parameters.Add("@CustomerId", System.Data.SqlDbType.Int).Value = customerId;
update.ExecuteNonQuery();
```

### MERGE / UPSERT

`MERGE` ที่ใช้เงื่อนไข `ON` เปรียบเทียบคอลัมน์เข้ารหัสต้องเคารพกฎเดียวกับ JOIN ในหัวข้อ 6.6 (deterministic + CEK/algorithm ตรงกัน) ส่วน `WHEN MATCHED THEN UPDATE SET` และ `WHEN NOT MATCHED THEN INSERT` ทำงานได้ตามปกติถ้าค่าที่ใส่มาจาก parameter หรือคอลัมน์ ciphertext ที่เข้ากันได้อยู่แล้ว:

```sql
MERGE dbo.Customer AS target
USING (VALUES (@NationalId, @FullName)) AS source (NationalId, FullName)
    ON target.NationalId = source.NationalId   -- ทั้งคู่ deterministic + CEK เดียวกัน
WHEN MATCHED THEN
    UPDATE SET FullName = source.FullName
WHEN NOT MATCHED THEN
    INSERT (NationalId, FullName) VALUES (source.NationalId, source.FullName);
```

## 6.9 การทดสอบ

- เขียน integration test ที่รันกับฐานข้อมูลจริง (หรือ container) ที่เปิด Always Encrypted เพื่อจับปัญหาด้าน connection string/driver ตั้งแต่ช่วง CI
- ทดสอบ query pattern ทุกจุดที่แตะคอลัมน์เข้ารหัส โดยเฉพาะ dynamic/reporting query ที่มักถูกมองข้าม
- ทดสอบ error path เมื่อ client ไม่มีสิทธิ์เข้าถึงคีย์ (จำลองสถานการณ์ unauthorized access)

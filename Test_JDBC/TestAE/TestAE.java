import java.sql.*;

public class TestAE {

    public static void main(String[] args) {

        try {

            // โหลด Microsoft JDBC Driver
            Class.forName("com.microsoft.sqlserver.jdbc.SQLServerDriver");

            String url =
                "jdbc:sqlserver://147.50.148.33;" +
                "databaseName=CoreClaim;" +
                "encrypt=true;" +
                "trustServerCertificate=true;" +
                "columnEncryptionSetting=Enabled;";

            String user = "devdba";
            String password = "-v300wfhxt";

            System.out.println("Connecting...");
            System.out.println(url);

            Connection conn = DriverManager.getConnection(url, user, password);

            System.out.println("Connected.");

            String sql =
                    "INSERT INTO ext.Person_Encrypt " +
                    "(PersonId, TitleId, IdentityCard) " +
                    "VALUES (?, ?, ?)";

            PreparedStatement ps = conn.prepareStatement(sql);

            ps.setInt(1, 999999);
            ps.setInt(2, 1);
            ps.setNString(3, "1234567890123");

            System.out.println("Executing Insert...");

            ps.executeUpdate();

            System.out.println("Insert Success");

            ps.close();

            PreparedStatement ps2 = conn.prepareStatement(
                    "SELECT IdentityCard FROM ext.Person_Encrypt WHERE PersonId=?");

            ps2.setInt(1, 999999);

            ResultSet rs = ps2.executeQuery();

            while (rs.next()) {
                System.out.println("IdentityCard = " + rs.getString(1));
            }

            rs.close();
            ps2.close();
            conn.close();

            System.out.println("Done.");

        } catch (Exception e) {
            e.printStackTrace();

             Throwable t = e.getCause();
    while (t != null) {
        System.out.println("CAUSE: " + t.getMessage());
        t.printStackTrace();
        t = t.getCause();
    }
        }

    }
}
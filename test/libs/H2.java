import java.sql.*;
import java.util.*;

// H2, an in-memory database: DDL, inserts with a prepared statement and a
// batch, joins, grouping, window functions, a transaction rolled back,
// constraint errors, metadata, and types (decimals, dates, arrays, JSON).
public class H2 {
    public static void main(String[] a) throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:h2:mem:t;DB_CLOSE_DELAY=-1", "sa", "")) {
            try (Statement s = c.createStatement()) {
                s.execute("CREATE TABLE dept(id INT PRIMARY KEY, name VARCHAR(20) NOT NULL UNIQUE)");
                s.execute("CREATE TABLE emp(id IDENTITY, name VARCHAR(30), dept INT REFERENCES dept(id), salary DECIMAL(10,2), hired DATE, tags VARCHAR ARRAY, info JSON)");
                s.execute("INSERT INTO dept VALUES (1,'eng'),(2,'ops'),(3,'empty')");
            }
            try (PreparedStatement p = c.prepareStatement("INSERT INTO emp(name, dept, salary, hired, tags, info) VALUES (?,?,?,?,?,? FORMAT JSON)")) {
                Object[][] rows = { {"ann", 1, "120000.50", "2020-01-15", new String[]{"a", "b"}, "{\"lvl\":3}"},
                                    {"bob", 1, "95000", "2021-06-01", new String[]{}, "{\"lvl\":1}"},
                                    {"cyd", 2, "70000.25", "2019-11-30", new String[]{"x"}, "{\"lvl\":2}"},
                                    {"dee", 2, "88000", "2022-03-03", null, null} };
                for (Object[] r : rows) {
                    p.setString(1, (String) r[0]); p.setInt(2, (Integer) r[1]); p.setBigDecimal(3, new java.math.BigDecimal((String) r[2]));
                    p.setDate(4, java.sql.Date.valueOf((String) r[3])); p.setArray(5, r[4] == null ? null : c.createArrayOf("VARCHAR", (Object[]) r[4]));
                    p.setString(6, (String) r[5]); p.addBatch();
                }
                System.out.println("batch " + Arrays.toString(p.executeBatch()));
            }
            query(c, "SELECT d.name, COUNT(e.id), SUM(e.salary), MAX(e.hired) FROM dept d LEFT JOIN emp e ON e.dept = d.id GROUP BY d.name ORDER BY d.name");
            query(c, "SELECT name, salary, RANK() OVER (PARTITION BY dept ORDER BY salary DESC), ROUND(AVG(salary) OVER (), 2) FROM emp ORDER BY id");
            query(c, "SELECT name, CARDINALITY(tags), CAST(info AS VARCHAR), CAST(JSON_OBJECT('n': name, 'd': dept) AS VARCHAR) FROM emp ORDER BY name");
            query(c, "SELECT DATEADD(MONTH, 1, DATE '2024-01-31'), DATEDIFF(DAY, DATE '2024-01-01', DATE '2024-03-01'), EXTRACT(DOW FROM DATE '2024-05-05')");
            query(c, "SELECT REGEXP_REPLACE('a1b22', '[0-9]+', '#'), UPPER('straße'), LPAD('7', 3, '0'), CAST(1.0/3 AS DECIMAL(10,4)), 2147483647 + 1");
            query(c, "WITH RECURSIVE f(n, v) AS (SELECT 1, CAST(1 AS BIGINT) UNION ALL SELECT n+1, v*(n+1) FROM f WHERE n < 20) SELECT MAX(v) FROM f");
            c.setAutoCommit(false);
            try (Statement s = c.createStatement()) { s.executeUpdate("UPDATE emp SET salary = salary * 2"); c.rollback(); }
            c.setAutoCommit(true);
            query(c, "SELECT SUM(salary) FROM emp");
            try (Statement s = c.createStatement()) { s.execute("INSERT INTO dept VALUES (4, 'eng')"); } catch (SQLException e) { System.out.println("unique " + e.getSQLState() + " " + e.getErrorCode()); }
            try (Statement s = c.createStatement()) { s.execute("INSERT INTO emp(name, dept) VALUES ('x', 9)"); } catch (SQLException e) { System.out.println("fk " + e.getSQLState() + " " + e.getErrorCode()); }
            try (Statement s = c.createStatement()) { s.execute("SELEKT 1"); } catch (SQLException e) { System.out.println("syntax " + e.getSQLState() + " " + e.getErrorCode()); }
            DatabaseMetaData md = c.getMetaData();
            System.out.println("meta " + md.getDatabaseProductName() + " " + md.getDriverMajorVersion());
            try (ResultSet rs = md.getColumns(null, "PUBLIC", "EMP", null)) { List<String> cols = new ArrayList<>(); while (rs.next()) cols.add(rs.getString("COLUMN_NAME") + ":" + rs.getString("TYPE_NAME")); System.out.println("cols " + cols); }
            Thread[] ts = new Thread[4]; int[] counts = new int[4];
            for (int i = 0; i < 4; i++) { int k = i; ts[i] = new Thread(() -> { try (Connection cc = DriverManager.getConnection("jdbc:h2:mem:t", "sa", ""); Statement s = cc.createStatement(); ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM emp WHERE dept <= " + (k % 2 + 1))) { rs.next(); counts[k] = rs.getInt(1); } catch (SQLException e) { counts[k] = -1; } }); ts[i].start(); }
            for (Thread t : ts) t.join();
            System.out.println("threads " + Arrays.toString(counts));
        }
    }
    static void query(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            int n = rs.getMetaData().getColumnCount();
            StringBuilder sb = new StringBuilder();
            while (rs.next()) { sb.append('['); for (int i = 1; i <= n; i++) { if (i > 1) sb.append(", "); Object o = rs.getObject(i); sb.append(o instanceof Object[] arr ? Arrays.toString(arr) : o instanceof Array ar ? Arrays.toString((Object[]) ar.getArray()) : String.valueOf(o)); } sb.append(']'); }
            System.out.println(sb);
        } catch (SQLException e) { System.out.println("error " + e.getSQLState() + " " + e.getMessage().lines().findFirst().orElse("")); }
    }
}

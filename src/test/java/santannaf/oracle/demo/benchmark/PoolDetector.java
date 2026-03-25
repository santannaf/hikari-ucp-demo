package santannaf.oracle.demo.benchmark;

import javax.sql.DataSource;

public final class PoolDetector {

    private PoolDetector() {}

    public static String detect(DataSource dataSource) {
        String className = dataSource.getClass().getName();
        if (className.contains("Hikari")) {
            return "HikariCP";
        }
        if (className.contains("oracle.ucp") || className.contains("PoolDataSource")) {
            return "Oracle UCP";
        }
        return className;
    }
}

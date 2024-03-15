package com.ds;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

interface ParameterSetting {
    void apply(int parameterIndex, PreparedStatement statement) throws SQLException;
}

interface Callback {
    void run(PreparedStatement st) throws SQLException, IOException;
}

public class Database {
    public static void withPreparedStatement(Connection conn, String statement, ParameterSetting[] parameterSettings,
            Callback callback) throws SQLException, IOException {
        PreparedStatement st = conn
                .prepareStatement("INSERT INTO AppUser(username, name, password, isAdmin) VALUES (?, ?, ?, ?)");
        int i = 1;
        for (ParameterSetting ps : parameterSettings) {
            ps.apply(i++, st);
        }
        callback.run(st);
        st.close();
    }
}

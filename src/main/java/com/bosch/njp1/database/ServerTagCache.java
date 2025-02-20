package com.bosch.njp1.database;

import com.bosch.njp1.redis.Redis;

import java.sql.*;
import java.util.concurrent.Executors;

public class ServerTagCache {

    public static Connection getConnection(String url,String username,String password) throws SQLException {
        return DriverManager.getConnection(url, username, password);
    }

    public static void init(String url, String username, String password, String sql, Redis redis) {
        try (Connection conn = getConnection(url,username,password);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {

            while (rs.next()) {
                String Channel_Name = rs.getString("Channel_Name");
                String Machine_Name = rs.getString("Machine_Name");
                String Tag_Name = rs.getString("Tag_Name");
                String Data_Type = rs.getString("Data_Type");
                redis.write("Tag:"+Channel_Name + "." + Machine_Name + "." + Tag_Name, Data_Type);
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }

        Executors.newScheduledThreadPool(1).scheduleAtFixedRate(() -> {
            try (Connection conn = getConnection(url,username,password);
                 Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(sql)) {

                while (rs.next()) {
                    String Channel_Name = rs.getString("Channel_Name");
                    String Machine_Name = rs.getString("Machine_Name");
                    String Tag_Name = rs.getString("Tag_Name");
                    String Data_Type = rs.getString("Data_Type");
                    redis.write("Tag:"+Channel_Name + "." + Machine_Name + "." + Tag_Name, Data_Type);

                }
            } catch (SQLException e) {
                e.printStackTrace();
            }
        }, 0, 30, java.util.concurrent.TimeUnit.MINUTES);
    }
}

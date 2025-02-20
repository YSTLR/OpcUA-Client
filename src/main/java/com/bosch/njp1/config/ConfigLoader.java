package com.bosch.njp1.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Paths;

public class ConfigLoader {

    public static ApplicationConfig getConfig() {
        String configFilePath = Paths.get(System.getProperty("user.dir"), "application.yml").toString();
        ApplicationConfig config = null;
        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        try (InputStream is = new FileInputStream(configFilePath)) {
            if (null == is) {
                throw new IllegalArgumentException("file not found!");
            } else {
                config = mapper.readValue(is, ApplicationConfig.class);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        return config;
    }
}

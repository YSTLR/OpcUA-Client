package com.bosch.njp1.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.io.InputStream;

public class ConfigLoader {

    public static ApplicationConfig getConfig() {
        ApplicationConfig config = null;
        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        try (InputStream is = ConfigLoader.class.getClassLoader().getResourceAsStream("application.yml")) {
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

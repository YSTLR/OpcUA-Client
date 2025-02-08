package com.bosch.njp1.util;

import com.bosch.njp1.config.ApplicationConfig;

public class ApplicationUtil {

    public static String parseNodeParam(String namespace, String group, String key){
        return "ns="+ namespace +";s=" + group+"."+key;
    }
    public static String parseNodeParam(String namespace, String tag){
        return "ns="+ namespace +";s=" + tag;
    }

}

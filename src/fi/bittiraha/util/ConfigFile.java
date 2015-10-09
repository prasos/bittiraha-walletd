package fi.bittiraha.util;

import java.util.*;

/**
 * Property file reader which contains handy content validation and
 * shortcuts for common data types. Best practise is to load
 * configuration file and then run getter for each parameter in use in
 * a try block to catch validation errors. Then you can safely use
 * getters without fear of validation errors.
 */ 
public class ConfigFile extends Properties {

    public ConfigFile() {
    }
    
    public ConfigFile(Properties defaults) {
	super(defaults);
    }

    public boolean getBoolean(String k) {
	String v = super.getProperty(k);
	if (v == null) throw new ConfigFieldException(k,"is null");
	switch (v.toLowerCase()) {
	case "1": return true;
	case "0": return false;
	case "true": return true;
	case "false": return false;
	default: throw new ConfigFieldException(k,"is not boolean (true, false, 0, or 1)");
	}

    }

    public String getString(String k) {
	String v = super.getProperty(k);
	if (v == null) throw new ConfigFieldException(k,"is null");
	return v;
    }
    
    /**
     * Validation excption. This is runtime exception so remember to
     * check exceptions if you don't want to exit in case of an error.
     */
    public static class ConfigFieldException extends RuntimeException {
	public ConfigFieldException(String k, String msg) {
	    super("Key "+k+" "+msg+" in configuration file");
	}
    }
}

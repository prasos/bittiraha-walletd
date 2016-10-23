package fi.bittiraha.util;

import java.math.BigDecimal;
import java.util.*;

/**
 * Property file reader which contains handy content validation and
 * shortcuts for common data types. Best practise is to load
 * configuration file and then set defaults for each parameter in use
 * in a try block to catch validation errors. Then you can safely use
 * getters without fear of validation errors.
 */ 
public class ConfigFile extends Properties {

    public ConfigFile() {
    }
    
    public ConfigFile(Properties defaults) {
	super(defaults);
    }

    public void defaultBoolean(String k, boolean def) {
	try {
	    getBoolean(k);
	} catch (ConfigFieldNullException _) {
	    super.setProperty(k, def ? "1" : "0");
	}
    }

    public boolean getBoolean(String k) {
	String v = super.getProperty(k);
	if (v == null) throw new ConfigFieldNullException(k);
	switch (v.toLowerCase()) {
	case "1": return true;
	case "0": return false;
	case "true": return true;
	case "false": return false;
	default: throw new ConfigFieldException(k,"is not boolean (true, false, 0, or 1)");
	}

    }

	public void defaultInteger(String k,int def) {
		try {
			getString(k);
		} catch (ConfigFieldNullException _) {
			super.setProperty(k,Integer.toString(def));
		}
	}

	public int getInteger(String k) {
		String v = super.getProperty(k);
		if (v == null) throw new ConfigFieldNullException(k);
		try {
			return Integer.parseInt(v);
		} catch (NumberFormatException _) {
			throw new ConfigFieldException(k,"Invalid format for Integer");
		}
	}

	public void defaultBigDecimal(String k,BigDecimal def) {
		try {
			getString(k);
		} catch (ConfigFieldNullException _) {
			super.setProperty(k,def.toString());
		}
	}

	public BigDecimal getBigDecimal(String k) {
		String v = super.getProperty(k);
		if (v == null) throw new ConfigFieldNullException(k);
		try {
			return new BigDecimal(v);
		} catch (NumberFormatException _) {
			throw new ConfigFieldException(k,"Invalid format for BigDecimal");
		}
	}

	public void defaultString(String k, String def) {
	try {
	    getString(k);
	} catch (ConfigFieldNullException _) {
	    super.setProperty(k, def);
	}
    }

    public String getString(String k) {
	String v = super.getProperty(k);
	if (v == null) throw new ConfigFieldNullException(k);
	return v;
    }
    
    /**
     * Validation exception. This is runtime exception so remember to
     * check exceptions if you don't want to exit in case of an error.
     */
    public static class ConfigFieldException extends RuntimeException {
	public ConfigFieldException(String k, String msg) {
	    super("Key "+k+" "+msg+" in configuration file");
	}
    }

    /**
     * Validation exception. This is used when null value is
     * encountered.
     */
    public static class ConfigFieldNullException extends ConfigFieldException {
	public ConfigFieldNullException(String k) {
	    super(k,"is null");
	}
    }
}

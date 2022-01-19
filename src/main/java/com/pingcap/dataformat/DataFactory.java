package com.pingcap.dataformat;

import java.util.Map;
import com.pingcap.enums.Model;

public class DataFactory implements DataFormatInterface {

	private DataFormatInterface formatInterface = null;
	
	public static DataFactory getInstance(String importMode,Map<String, String> properties) {
		return new DataFactory(importMode,properties);
	}
	
	public DataFactory(String importMode,Map<String, String> properties) {
		if(Model.JSON_FORMAT.equals(importMode)) {
			formatInterface = new DataFormatForJson(properties);
		}
		else if(Model.CSV_FORMAT.equals(importMode)) {
			formatInterface = new DataFormatForCsv(properties);
		}
	}

	@Override
	public boolean formatToKeyValue(String scenes,String line,DataFormatCallBack dataFormatCallBack) throws Exception {
		return formatInterface.formatToKeyValue(scenes,line,dataFormatCallBack);
	}

	@Override
	public boolean unFormatToKeyValue(String scenes, String key,
			String value, UnDataFormatCallBack unDataFormatCallBack) throws Exception {
		return formatInterface.unFormatToKeyValue(scenes,key,value,unDataFormatCallBack);
	}

}
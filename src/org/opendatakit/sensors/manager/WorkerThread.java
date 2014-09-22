/*
 * Copyright (C) 2013 University of Washington
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package org.opendatakit.sensors.manager;

import java.util.Iterator;
import java.util.List;

import org.json.JSONArray;
import org.json.JSONObject;
import org.opendatakit.common.android.database.DataModelDatabaseHelper;
import org.opendatakit.common.android.database.DataModelDatabaseHelperFactory;
import org.opendatakit.common.android.utilities.ODKDatabaseUserDefinedTypes;
import org.opendatakit.common.android.utilities.ODKDatabaseUtils;
import org.opendatakit.sensors.DataSeries;
import org.opendatakit.sensors.DriverType;
import org.opendatakit.sensors.ODKSensor;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Bundle;
import android.util.Log;

/**
 * 
 * @author wbrunette@gmail.com
 * @author rohitchaudhri@gmail.com
 * 
 */
public class WorkerThread extends Thread {
	private static final String TAG = "WorkerThread";
	private static final String jsonTableStr = "table";
	private static final String jsonNameStr = "name";
	private static final String jsonColumnStr = "columns";
	private static final String jsonTypeStr = "type";
	
	private boolean isRunning;
	private Context serviceContext;
	private ODKSensorManager sensorManager;

	public WorkerThread(Context context, ODKSensorManager manager) {
		super("WorkerThread");
		isRunning = true;
		serviceContext = context;
		sensorManager = manager;
	}

	public void stopthread() {
		isRunning = false;
		this.interrupt();
	}

	@Override
	public void run() {
		Log.d(TAG, "worker thread started");		
		
		while(isRunning) {
			try {
				for(ODKSensor sensor : sensorManager.getSensorsUsingAppForDatabase()) {
					moveSensorDataToCP(sensor);
				}
				
				Thread.sleep(1000);
			}
			catch(InterruptedException iex) {
				iex.printStackTrace();
			}
		}
	}

	private void moveSensorDataToCP(ODKSensor aSensor) {		
		if (aSensor != null) {			
			List<Bundle> bundles = aSensor.getSensorData(0);//XXX for now this gets all data fm sensor			
			if(bundles == null) {
				Log.e(TAG,"WTF null list of bundles~");
			}
			else {
				Iterator<Bundle> iter = bundles.iterator();
				while(iter.hasNext()) {
					Bundle aBundle = iter.next();	

					DriverType driver = sensorManager.getSensorDriverType(aSensor.getSensorID());
					if (driver != null && driver.getTableDefinitionStr() != null) {
						parseSensorDataAndInsertIntoTable(aSensor, driver.getTableDefinitionStr(), aBundle);
					}
					
				}
			}
		}
	}
	
	
	private void parseSensorDataAndInsertIntoTable(ODKSensor aSensor, String strTableDef, Bundle dataBundle){
		JSONObject jsonTableDef = null;
		ContentValues tablesValues = new ContentValues();
		
		DataModelDatabaseHelper dbh = DataModelDatabaseHelperFactory.getDbHelper(serviceContext, aSensor.getAppNameForDatabase());
		SQLiteDatabase db = dbh.getWritableDatabase();
		
		StringBuilder dbValuesToWrite = new StringBuilder();
		
		try {
			jsonTableDef = new JSONObject(strTableDef);
			
			String tableName = jsonTableDef.getJSONObject(jsonTableStr).getString(jsonNameStr);
			
			if (tableName != null) {
			    Cursor cursor = db.rawQuery("select DISTINCT tbl_name from sqlite_master where tbl_name = '"+tableName+"'", null);
			    if(cursor!=null) {
			        if(cursor.getCount() <= 0) {
			            sensorManager.parseDriverTableDefintionAndCreateTable(aSensor.getSensorID(), aSensor.getAppNameForDatabase(), db);
			        }
			        cursor.close();
			    }
			}
    	   
   			// Create the columns for the driver table
   			JSONArray colJsonArray = jsonTableDef.getJSONObject(jsonTableStr).getJSONArray(jsonColumnStr);
   			
   			for (int i = 0; i < colJsonArray.length(); i++) {
   				JSONObject colJson = colJsonArray.getJSONObject(i);
   				String colName = colJson.getString(jsonNameStr);
   				String colType = colJson.getString(jsonTypeStr);

   				if (colType.equals(ODKDatabaseUserDefinedTypes.STRING) || colType.equals(ODKDatabaseUserDefinedTypes.MIMEURI) ||
   			    	    colType.equals(ODKDatabaseUserDefinedTypes.DATE) || colType.equals(ODKDatabaseUserDefinedTypes.DATETIME) ||
   			    	    colType.equals(ODKDatabaseUserDefinedTypes.TIME) || colType.equals(ODKDatabaseUserDefinedTypes.ARRAY)) {
					if (colName.equals(DataSeries.SENSOR_ID)) { 
						tablesValues.put(colName, aSensor.getSensorID());
						dbValuesToWrite.append(colName).append("=").append(aSensor.getSensorID()).append(" ");
					} else if (dataBundle.getString(colName) != null) {
						String colData = dataBundle.getString(colName); 
						tablesValues.put(colName, colData);
						dbValuesToWrite.append(colName).append("=").append(colData).append(" ");
					}
   				} else if (colType.equals(ODKDatabaseUserDefinedTypes.BOOLEAN) || colType.equals(ODKDatabaseUserDefinedTypes.INTEGER)) {
					int colData = dataBundle.getInt(colName);
					tablesValues.put(colName, colData);
					dbValuesToWrite.append(colName).append("=").append(colData).append(" ");
					
				} else if (colType.equalsIgnoreCase(ODKDatabaseUserDefinedTypes.NUMBER) || colType.equalsIgnoreCase(ODKDatabaseUserDefinedTypes.GEOPOINT)) {
					double colData = dataBundle.getDouble(colName);
					tablesValues.put(colName, colData);
					dbValuesToWrite.append(colName).append("=").append(colData).append(" ");
				} else {
	   			    Log.i(TAG, "parseSensorDataAndInsertIntoTable: Couldn't convert " + colType + " to a database type");
	   			}
   			}
   			
   			if (tablesValues.size() > 0) {
   				Log.i(TAG,"Writing db values "+ dbValuesToWrite.toString() +" for sensor:" + aSensor.getSensorID());
   				ODKDatabaseUtils.writeDataIntoExistingDBTable(db, tableName, tablesValues);
   			}

        } catch (Exception e) {
        	e.printStackTrace();
        }
	    
	    db.close();
	}
}

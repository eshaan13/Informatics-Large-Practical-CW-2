package uk.ac.ed.inf.aqmaps;

import java.io.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodyHandlers;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.mapbox.geojson.*;

/**
 * The Class App.
 */
public class App
{
	
	// Class Variables
		
	/** GeoJSON points of the Drone Confinement Zone. */
	final static private  Point Forrest_Hill = Point.fromLngLat(-3.192473, 55.946233);
	final static private Point KFC = Point.fromLngLat(-3.184319, 55.946233);
	final static private Point Buccleuch_St_bus_stop = Point.fromLngLat(-3.184319, 55.942617);
	final static private Point Meadows_Top = Point.fromLngLat(-3.192473, 55.942617);
	
	/** List of edges of the Drone Confinement Zone */
	private static List<Point> droneConfinementZone = new ArrayList<Point>();
	
	/** dd/mm/yyyy when the drone has to reads the sensors */
	private String day;
	private String month;
	private String year;
	
	/** Starting position of the drone */
	private Position startingPosition;
	
	/** Port number of the seb server */
	private int portNumber;
	
	/** GeoJSON features of No Fly Zone Buildings */
	List<Polygon> noFlyZoneBuildings = new ArrayList<Polygon>();

	/** List of sensors to be read */
	List<Sensor> sensors = new ArrayList<Sensor>();
	
	/** List of moves made by the drone */
	private List<Position> moves;
	
	/** List of explored sensors */
	private List<Integer> exploredSensors;
	
	/** Indexes of the moves when drone reads a sensor */
	private List<Integer> moveWhileReading = new ArrayList<Integer>();
	
	
	
	// Methods
	
	/**
	 * Constructor to initialise the date and the starting position of the drone
	 * @param Date and starting position of the drone
	 */
	public App(String day, String month, String yr, Position startPos, int portNumber) {
		this.day = day;
		this.month = month;
		this.year = yr;
		this.startingPosition = startPos;
		this.portNumber = portNumber;
	}
	
	/**
	 * Method to return the RGB value for the reading
	 * @param reading reading of the sensor
	 * @return RGB string for that sensor
	 */
	private String getRGBValue(Sensor sensor) {
		
		if(sensor.getBattery() < 10.0 || sensor.getReading() == "null" || sensor.getReading() == "NaN")
			return "#000000";
		
		double reading = Double.parseDouble(sensor.getReading());
		if(reading >= 0 && reading < 32) // Green
			return "#00ff00";
		else if(reading >= 32 && reading < 64) // Medium Green
			return "#40ff00";
		else if(reading >= 64 && reading < 96) // Light Green
			return "#80ff00";
		else if(reading >= 96 && reading < 128) // Lime Green
			return "#c0ff00";
		else if(reading >= 128 && reading < 160) // Gold
			return "#ffc000";
		else if(reading >= 160 && reading < 192) // Orange
			return "#ff8000";
		else if(reading >= 192 && reading < 224) // Red / Orange
			return "#ff4000";
		else if(reading >= 224 && reading < 256) // Red
			return "#ff0000";
		return null;
	}
	
	private String getMarkerSymbol(Sensor sensor) {
		
		if(sensor.getBattery() < 10.0 || sensor.getReading() == "null" || sensor.getReading() == "NaN")
			return "cross";
		
		double reading = Double.parseDouble(sensor.getReading());
		if(reading >= 0 && reading < 128)
			return "lighthouse";
		else if(reading >= 128 && reading < 256)
			return "danger";
		return null;
	}
	
	
	/**
	 * Method to set up a connection with the HTTP Server.
	 * @param urlString Address of the file to be read
	 * @return response received from the server
	 * @throws IOException
	 * @throws InterruptedException
	 */
	private Object setConnection(String urlString) throws IOException, InterruptedException {
		final HttpClient client = HttpClient.newHttpClient();
		var request = HttpRequest.newBuilder().uri(URI.create(urlString)).build();
		var response = client.send(request, BodyHandlers.ofString());
		return response.body();
	}
	
	/**
	 * Method to read the No FlyZone file and initialise the global variable of buildings.
	 * @throws IOException
	 * @throws InterruptedException
	 */
	protected void noFlyZoneBuildings() throws IOException, InterruptedException {

		// Address of geoJson file of No Fly Zone Buildings
		String urlString = "http://localhost:" + portNumber + "/buildings/no-fly-zones.geojson";
		var response = setConnection(urlString);
		FeatureCollection fc = FeatureCollection.fromJson(response.toString());
		var noFlyZoneBuildingsFeatures = fc.features();
		for(Feature feature: noFlyZoneBuildingsFeatures)
			noFlyZoneBuildings.add((Polygon)feature.geometry());
	}
	
	
	/**
	 * Method to read the JSON file for Air Quality Data
	 * @throws IOException
	 * @throws InterruptedException
	 */
	private void airQualityData() throws IOException, InterruptedException {
		String urlString = "http://localhost:" + portNumber + "/maps/" + 
				year + "/" + month + "/" + day + "/air-quality-data.json";
		var response = setConnection(urlString);
		var listType = new TypeToken<ArrayList<Sensor>>() {}.getType();
		sensors = new Gson().fromJson(response.toString(), listType);
	}
	
	/**
	 * Method to read the JSON file for What Three Word
	 * @param words location of the sensor in what three word format
	 * @return an instance of what three word
	 * @throws IOException
	 * @throws InterruptedException
	 */
	protected WhatThreeWord whatThreeWord(String loc) throws IOException, InterruptedException {
		String words[] = loc.split("\\.", 3);
		String urlString = "http://localhost:" + portNumber + "/words/" + 
				words[0] + "/" + words[1] + "/" + words[2] + "/details.json";
		var response = setConnection(urlString);
		var wtw = new Gson().fromJson(response.toString(), WhatThreeWord.class);
		return wtw;
	}
	
	/**
	 * Method to get the list of sensors to be read
	 * @throws IOException
	 * @throws InterruptedException
	 */
	private void getSensors() throws IOException, InterruptedException {
		airQualityData(); // Read the air quality file for that day.
		for(int i = 0; i < sensors.size(); ++i) {
			Sensor sensor = sensors.get(i);
			String loc = sensor.getLocation();
			var wtw = whatThreeWord(loc);
			sensor.setPos(new Position(wtw.getCoordinates().getLongitude(), 
					wtw.getCoordinates().getLatitude()));
			sensor.setSensorNumber(i);
		}
	}
	
	private String toGeoJson() {
		
		// Adding the GeoJson features of the moves made by the drone
		var points = new ArrayList<Point>();
		for(Position move: moves)
			points.add(Point.fromLngLat(move.getLng(), move.getLat()));
		
		LineString lnst = LineString.fromLngLats(points);
		Feature feature = Feature.fromGeometry((Geometry)lnst);
		var features = new ArrayList<Feature>();
		features.add(feature);
		
		// Adding the GeoJson features of the sensors
		for(Sensor sensor: sensors) {
			Point pt = Point.fromLngLat(sensor.getPos().getLng(), sensor.getPos().getLat());
			Feature featureSensor = Feature.fromGeometry((Geometry) pt);
			featureSensor.addStringProperty("marker-size", "medium");
			featureSensor.addStringProperty("location", sensor.getLocation());
			featureSensor.addStringProperty("rgb-string", sensor.getRgbValue());
			featureSensor.addStringProperty("marker-color", sensor.getRgbValue());
			if(sensor.isVisited()) // Only visited sensors
				featureSensor.addStringProperty("marker-symbol", sensor.getMarkerSymbol());
			features.add(featureSensor);
		}
		
		
		// REMOVE
		
		// REMOVE Adding the GeoJson features of the Drone Confinement Zone
		droneConfinementZone.add(Forrest_Hill);// adding the last point of the polygon
		Polygon polyDCZ = Polygon.fromLngLats(List.of(droneConfinementZone));
		Feature featureDCZ = Feature.fromGeometry((Geometry) polyDCZ);
		featureDCZ.addStringProperty("fill", "#ffffff");
		featureDCZ.addNumberProperty("fill-opacity", 0);
		features.add(featureDCZ);
		
		// REMOVE Adding the Starting Point
		Point start = Point.fromLngLat(startingPosition.getLng(), startingPosition.getLat());
		Feature f = Feature.fromGeometry((Geometry) start);
		f.addStringProperty("marker-size", "large");
		f.addStringProperty("marker-color", "#9400D3");
		features.add(f);
		
		// REMOVE Adding the No Fly Zones
		features.add(Feature.fromGeometry((Geometry)noFlyZoneBuildings.get(0)));
		features.add(Feature.fromGeometry((Geometry)noFlyZoneBuildings.get(1)));
		features.add(Feature.fromGeometry((Geometry)noFlyZoneBuildings.get(2)));
		features.add(Feature.fromGeometry((Geometry)noFlyZoneBuildings.get(3)));
		
		// Feature Collection of all the features
		FeatureCollection fc = FeatureCollection.fromFeatures(features);
		return fc.toJson();
	}
	
	private void setSensorProperties() {
		for(Sensor sensor: sensors) {
			if(sensor.isVisited()) { // Visited sensors
				sensor.setRgbValue(getRGBValue(sensor));
				sensor.setMarkerSymbol(getMarkerSymbol(sensor));
			}
			else sensor.setRgbValue("#aaaaaa"); // Not visited sensors	
		}
	}
	
	private void prepareDrone() {
		
    	droneConfinementZone.add(Forrest_Hill);
    	droneConfinementZone.add(KFC);
    	droneConfinementZone.add(Buccleuch_St_bus_stop);
    	droneConfinementZone.add(Meadows_Top);
		Drone drone = new Drone(startingPosition, sensors, noFlyZoneBuildings, droneConfinementZone);
		moveWhileReading = drone.traverseSensors();
		drone.homeComing();
		moves = drone.getMoves();
		exploredSensors = drone.getExploredSensors();
		System.out.println("Date: " + day + "/" + month + "/" + year + "\nMoves count: " + drone.getMovesCount() + 
				"\nExplored sensors: " + drone.getExploredSensors() + "\nNo of sensors read: " + drone.getExploredSensors().size());
	}
	
	private void generateOutputFiles() {
    		
		String fileNameTXT = "flightpath-" + day + "-" + month + "-" + year + ".txt";
		String fileNameGeoJson = "readings-" + day + "-" + month + "-" + year + ".geojson";
    			
		// Creating TXT files
		try {
			File myObj = new File(fileNameTXT);
			myObj.createNewFile();
		} catch (IOException e) {
			System.out.println("An error occurred: " + e);
			e.printStackTrace();
		}
    			
		// Creating GeoJson files
		try {
			File myObj = new File("/Users/eshaangupta/Desktop/ILP/heatmap/ilp-results/" + fileNameGeoJson);
			myObj.createNewFile();
		} catch (IOException e) {
			System.out.println("An error occurred: " + e);
			e.printStackTrace();
		}	
		writeOutputFiles(fileNameTXT, fileNameGeoJson);
	}
	
	private void writeOutputFiles(String fileNameTXT, String fileNameGeoJson) {
		
		String s_txt = "";
		for(int i = 0; i < moves.size() - 1; ++i) {
			Position beforeMove = moves.get(i);
			Position afterMove = moves.get(i + 1);
			int sensorIndex = moveWhileReading.indexOf(i+1);
			String sensorLoc;
			if(sensorIndex != -1)
				sensorLoc = sensors.get((exploredSensors.get(sensorIndex))).getLocation();
			else sensorLoc = null; 
			
			s_txt += (i + 1) + "," + beforeMove.getLng() + "," + beforeMove.getLat() + "," + 
								beforeMove.findDirection(afterMove) + "," + afterMove.getLng() + 
									"," + afterMove.getLat() + "," + sensorLoc + "\n";
		}
		writeFileTXT(fileNameTXT, s_txt);
		writeFileGeoJson(fileNameGeoJson, toGeoJson());	
	}
		
	// Writing TXT output files
	private void writeFileTXT(String fileNameTXT, String text) {
		try {
			FileWriter fr = new FileWriter(fileNameTXT);
			fr.write(text);
			fr.close();
		} catch (IOException e) {
			System.out.println("An error occurred: " + e);
			e.printStackTrace();
		}
	}
		
	// Writing GeoJson output files
	private void writeFileGeoJson(String fileNameGeoJson, String text) {
		try {
			FileWriter fr = new FileWriter(fileNameGeoJson);
			fr.write(text);
			fr.close();
		} catch (IOException e) {
			System.out.println("An error occurred: " + e);
			e.printStackTrace();
		}
	}
	
	
	/**
	 * Main method to instantiate the class App.java and call methods accordingly
	 * to read the sensors.
	 * @param args date and the starting position of the drone
	 * @throws IOException
	 * @throws InterruptedException 
	 */
    public static void main( String[] args ) throws IOException, InterruptedException {
    	
    	Position startingPoint = new Position(Double.parseDouble(args[4]), Double.parseDouble(args[3]));
    	App obj = new App(args[0], args[1], args[2], startingPoint, Integer.parseInt(args[6]));
    	
    	List<Point> droneConfinementZone = new ArrayList<Point>();
    	droneConfinementZone.add(Forrest_Hill);
    	droneConfinementZone.add(KFC);
    	droneConfinementZone.add(Buccleuch_St_bus_stop);
    	droneConfinementZone.add(Meadows_Top);
    	
    	// Checking if the starting position of the drone is outside the Drone Confinement Zone
    	if(!startingPoint.insideDroneConfinementZone(droneConfinementZone)) {
    		System.out.println("Drone starting position outside Drone Confinement Zone.");
    		System.exit(0);
    	}
    	
    	obj.noFlyZoneBuildings();
    	obj.getSensors();
    	obj.prepareDrone();
    	obj.setSensorProperties();
    	obj.generateOutputFiles();
    	
    	
    	
    	
    	
//    	Point test_In_DCZ = Point.fromLngLat(-3.1867003440856934, 55.944345561848124);
//    	Point test_Out_DCZ = Point.fromLngLat(-3.188333138823509, 55.942615746202236);
//    	System.out.println(obj.insideDroneConfinementZone(test_In_DCZ));
    	
//    	Point test_AT = Point.fromLngLat(-3.1867003440856934, 55.944345561848124);
//    	Point test_DHT = Point.fromLngLat(-3.186507225036621, 55.94327009236843);
//    	Point test_LIB = Point.fromLngLat(-3.188985586166382, 55.942771400854795);
//    	Point test_INF = Point.fromLngLat(-3.187258243560791, 55.94510858632841);

//    	System.out.println("Final: " + (obj.insideNoFlyZone(test_INF)));
		  	
    }
}
package io.test.automation.robodriver;

import java.awt.*;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.openqa.selenium.WebDriverException;
import org.openqa.selenium.interactions.Sequence;
import org.openqa.selenium.remote.Command;
import org.openqa.selenium.remote.CommandExecutor;
import org.openqa.selenium.remote.DriverCommand;
import org.openqa.selenium.remote.Response;

import io.test.automation.robodriver.internal.LoggerUtil;
import io.test.automation.robodriver.internal.RoboScreen;
import io.test.automation.robodriver.internal.RoboScreenRectangle;
import io.test.automation.robodriver.internal.RoboUtil;

public class RoboDriverCommandExecutor implements CommandExecutor {
	
	private static Logger LOGGER = LoggerUtil.get(RoboDriverCommandExecutor.class);
	
	private Pattern xpathWithIndex = Pattern.compile("/*screen\\[(\\d+)\\]");

	private RoboDriver driver;
			
	public void setDriver(RoboDriver driver) {
		this.driver = driver;
	}
	
	// TODO use XPath API instead of simple parsing
	@Override
	public Response execute(Command command) throws IOException {
		LOGGER.log(Level.FINE, "command: {0}", command.toString());
		Response response = new Response();
		if (DriverCommand.SEND_KEYS_TO_ACTIVE_ELEMENT.equals(command.getName())) { 
			GraphicsDevice device = RoboUtil.getDefaultDevice(); // TODO use active screen
			Robot robot = RoboUtil.getRobot(device);
			CharSequence[] keysToSend = (CharSequence[]) command.getParameters().get("value");
			RoboUtil.sendKeys(robot, keysToSend);
		} else if (DriverCommand.MOUSE_UP.equals(command.getName())) {
			GraphicsDevice device = getDevice(command.getParameters());
			RoboUtil.mouseUp(device);
		} else if (DriverCommand.MOUSE_DOWN.equals(command.getName())) {
			GraphicsDevice device = getDevice(command.getParameters());
			RoboUtil.mouseDown(device);
		} else if (DriverCommand.MOVE_TO.equals(command.getName())) {
			Map<String, ?> parameters = command.getParameters();
			if (parameters.containsKey("element")) {
				String deviceId = (String) parameters.get("element");
				GraphicsDevice device = RoboUtil.getDeviceById(deviceId);
				Robot robot = RoboUtil.getRobot(device);
				if (parameters.containsKey("xoffset")) {
					int xoffset =  new BigDecimal((Long)parameters.get("xoffset")).intValueExact();
					int yoffset = new BigDecimal((Long)parameters.get("yoffset")).intValueExact();
					robot.mouseMove(xoffset, yoffset);
				} else {
					// move to center
					Rectangle bounds = device.getDefaultConfiguration().getBounds();
					robot.mouseMove((int) bounds.getCenterX(), (int) bounds.getCenterY());
				}
			} else if (parameters.containsKey("xoffset")) {
				Robot robot = RoboUtil.getRobot(RoboUtil.getDefaultDevice());
				Point location = MouseInfo.getPointerInfo().getLocation();
				Long xoffset = (Long) parameters.get("xoffset");
				Long yoffset = (Long) parameters.get("yoffset");
				int x = location.x + new BigDecimal(xoffset).intValueExact();
				int y = location.y + new BigDecimal(yoffset).intValueExact();
				robot.mouseMove(x, y);
			}
		} else if (DriverCommand.FIND_ELEMENTS.equals(command.getName())) {
			List<RoboScreen> allElements = RoboScreen.getAllScreens(driver);
			response.setValue(allElements);
		} else if (DriverCommand.FIND_ELEMENT.equals(command.getName())) {
			Map<String, ?> parameters = command.getParameters();
			String value = parameters.get("value").toString().toLowerCase();
			if (! value.contains("screen")) {
				throw new WebDriverException("connot find '" + value + "'");
			}
			if (value.contains("default")) {
				RoboScreen screen = RoboScreen.getDefaultScreen(driver);
				response.setValue(screen);
			} else if (value.endsWith("screen")) {
				RoboScreen screen = RoboScreen.getScreen(0, driver);
				response.setValue(screen);
			} else {
				Matcher matcher = xpathWithIndex.matcher(value);
				if (matcher.find()) {
					String index = matcher.group(1);
					try {
						RoboScreen screen = RoboScreen.getScreen(Integer.parseInt(index), driver);
						response.setValue(screen);
					} catch (Exception e) {
						throw new IOException("Cannot find screen with index = '" + index + "'");
					}
				}
			}
		} else if (DriverCommand.FIND_CHILD_ELEMENT.equals(command.getName())) {
			Map<String, ?> parameters = command.getParameters();
			String id = parameters.get("id").toString().toLowerCase();
			RoboScreen screen = RoboScreen.getScreenById(id);
			assert "xpath".equals(parameters.get("using").toString().toLowerCase());
			String value = parameters.get("value").toString().toLowerCase();
			if (! value.contains("rectangle")) {
				throw new WebDriverException("connot find child '" + value + "' from device ID '" + id + "'");
			}
			if (value.contains("dim")) {
				String t1 = value.substring(value.indexOf("dim")).replace("dim='", "");
				String t2 = t1.substring(0, t1.indexOf('\''));
				String[] dimensionValues = t2.split(",");
				if (dimensionValues.length != 4) {
					throw new RuntimeException("invalid rectangle dimension: " + value);
				}
				int x = Integer.parseInt(dimensionValues[0]);
				int y = Integer.parseInt(dimensionValues[1]);
				int widht = Integer.parseInt(dimensionValues[2]);
				int height = Integer.parseInt(dimensionValues[3]);
				response.setValue(new RoboScreenRectangle(screen, x, y, widht, height));
			} else {
				throw new RuntimeException("no dimension attribute defining x,y,widht,height found, example '//rectangle[@dim='70,80,100,200']'");
			}
		} else if (DriverCommand.NEW_SESSION.equals(command.getName())) {
			response.setValue(new HashMap<>());
			response.setSessionId(Long.toString(System.currentTimeMillis()));
		} else if (DriverCommand.ACTIONS.equals(command.getName())) {
			handleActionsW3C_Selenium_3_4(command);
		}
		return response;
	}

	@SuppressWarnings("unchecked")
	private boolean handleActionsW3C_Selenium_3_4(Command command) {
		Set<?> actions = (Set<?>) command.getParameters().get("actions");
		for (Object action : actions) {
			if (action instanceof Sequence) {
				Sequence seq = (Sequence) action;
				Map<String, Object> encode = seq.encode();
				LOGGER.log(Level.FINE, "ACTION raw data: {0}", encode);
				List<Object> actionList = (List<Object>) encode.get("actions");
				
				GraphicsDevice device = null; // target device must be defined by one of the following actions
				for (Object actionObject : actionList) {
					Map<String, Object> actionDetails = (Map<String, Object>) actionObject;
					final Object targetObject = actionDetails.get("origin");
					if (targetObject == null) {
						LOGGER.log(Level.FINE, "No screen device defined, using default screen.");
						device = RoboUtil.getDefaultDevice();
					} else if (! (targetObject instanceof RoboScreen)) {
						if (device == null) { // expected that device was determined by the 'origin' of a previous action
							throw new RuntimeException(String.format("No device defined, maybe invalid target element type '%s', '%s' is needed.", targetObject.toString(), RoboScreen.class.getName()));
						}
					} else {
						device = ((RoboScreen)targetObject).getDevice();
					}
					String actionType = (String) actionDetails.get("type");
					LOGGER.log(Level.FINE, "action type = '{0}'", actionType);
					switch (actionType) {
					case "pointerMove": 
						Long tickDuration = (Long) actionDetails.get("duration");
						Integer movePosX = (Integer) actionDetails.get("x");
						Integer movePosY = (Integer) actionDetails.get("y");
						RoboUtil.mouseMove(device, tickDuration, movePosX, movePosY);
						break;
					case "pointerDown":
						RoboUtil.mouseDown(device);
						break;
					case "pointerUp":
						RoboUtil.mouseUp(device);
						break;
					}
				}
			}
		}
		LOGGER.log(Level.SEVERE, "unknown actions: {0}", actions);
		return false;
	}

	private GraphicsDevice getDevice(Map<String, ?> parameters) {
		if (parameters.containsKey("element")) {
			return RoboUtil.getDeviceById((String)parameters.get("element"));
		} else {
			return RoboUtil.getDefaultDevice();
		}
	}

}
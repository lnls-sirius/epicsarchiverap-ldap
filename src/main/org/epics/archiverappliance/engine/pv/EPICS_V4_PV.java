package org.epics.archiverappliance.engine.pv;

import java.lang.reflect.Constructor;
import java.util.Calendar;
import java.util.HashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.apache.log4j.Logger;
import org.epics.archiverappliance.config.ArchDBRTypes;
import org.epics.archiverappliance.config.ConfigService;
import org.epics.archiverappliance.config.MetaInfo;
import org.epics.archiverappliance.data.DBRTimeEvent;
import org.epics.pvaccess.client.Channel;
import org.epics.pvaccess.client.Channel.ConnectionState;
import org.epics.pvaccess.client.ChannelGet;
import org.epics.pvaccess.client.ChannelGetRequester;
import org.epics.pvaccess.client.ChannelProvider;
import org.epics.pvaccess.client.ChannelProviderRegistryFactory;
import org.epics.pvaccess.client.ChannelRequester;
import org.epics.pvdata.copy.CreateRequest;
import org.epics.pvdata.misc.BitSet;
import org.epics.pvdata.monitor.Monitor;
import org.epics.pvdata.monitor.MonitorElement;
import org.epics.pvdata.monitor.MonitorRequester;
import org.epics.pvdata.pv.Field;
import org.epics.pvdata.pv.MessageType;
import org.epics.pvdata.pv.PVStructure;
import org.epics.pvdata.pv.Status;
import org.epics.pvdata.pv.Structure;

public class EPICS_V4_PV implements PV, ChannelGetRequester, ChannelRequester, MonitorRequester {
	private static final Logger logger = Logger.getLogger(EPICS_V4_PV.class.getName());
	private static ChannelProvider channelProvider;

	/** Channel name. */
	final private String name = null;
	
	/**the meta info for this pv*/
	private MetaInfo totalMetaInfo = new MetaInfo();
	
	private PVConnectionState state = PVConnectionState.Idle;
	
	private Channel channel;
	
	/**configservice used by this pv*/
	final private ConfigService configservice = null;
	
	/** PVListeners of this PV */
	final private CopyOnWriteArrayList<PVListener> listeners = new CopyOnWriteArrayList<PVListener>();
	
	/**
	 * isConnected? <code>true</code> if we are currently connected (based on
	 * the most recent connection callback).
	 * <p>
	 * EPICS_V3_PV also runs notifyAll() on <code>this</code> whenever the
	 * connected flag changes to <code>true</code>.
	 */
	private volatile boolean connected = false;
	
	private boolean monitorIsDestroyed = false;
	
	/**
	 * isRunning? <code>true</code> if we want to receive value updates.
	 */
	private volatile boolean running = false;
	
	/**the DBRTimeEvent constructor for this pv*/
	private Constructor<? extends DBRTimeEvent> con;
	
	/**the current DBRTimeEvent*/
	private DBRTimeEvent dbrtimeevent;
	
	/**the ArchDBRTypes of this pv*/
	private ArchDBRTypes archDBRTypes = null;
	
	/**
	 * The JCA command thread that processes actions for this PV.
	 * This should be inherited from the ArchiveChannel.
	 */
	private int jcaCommandThreadId;

	/**
	 * If this pv is a meta field, then the metafield parent PV is where the data for this metafield is stored.
	 **/
	private PV parentPVForMetaField = null;

	/**Does this pv have one meta field archived?*/
	private boolean hasMetaField = false;

	/**
	 * If this pv has many meta fields archived, allarchiveFieldsData includes the meta field names and their values.
	 * allarchiveFieldsData is updated when meta field changes
	 * if this pv doesn't have meta field archived, this  is always  null.
	 */
	private ConcurrentHashMap<String, String> allarchiveFieldsData = null;
	
	/** Runtime fields that are not archived/stored are stored here */
	private ConcurrentHashMap<String, String> runTimeFieldsData = new ConcurrentHashMap<String, String>();
	
	/** if this pv has many meta fields archived,changedarchiveFieldsData includes the changed meta values and the field names*/
	private ConcurrentHashMap<String, String> changedarchiveFieldsData = null;
	
	/**we save all meta field once every day and lastTimeStampWhenSavingarchiveFields is when we save all last meta fields*/
	private Calendar lastTimeStampWhenSavingarchiveFields = null;
	
	/**this pv is meta field  or not*/
	private boolean isarchiveFieldsField = false;
	
	/** Store the value for this only in the runtime and not into the stores...*/
	private boolean isruntimeFieldField = false;
	/**
	 * the ioc host name where this pv is 
	 */
	private String hostName;

	private Monitor subscription = null;

	

	@Override
	public String getName() {
		return name;
	}

	@Override
	public void addListener(PVListener listener) {
		listeners.add(listener);
		if (running && isConnected()) { 
			listener.pvValueUpdate(this);
		}
	}

	@Override
	public void removeListener(PVListener listener) {
		listeners.remove(listener);
	}
	
	/** Notify all listeners. */
	private void fireDisconnected() {
		for (final PVListener listener : listeners) {
			listener.pvDisconnected(this);
		}
	}



	/** Notify all listeners. */
	private void fireValueUpdate() {
		for (final PVListener listener : listeners) {
			listener.pvValueUpdate(this);
		}
	}




	@Override
	public void start() throws Exception {
		if (running) {
			return;
		}

		running = true;
		this.connect();
	}

	@Override
	public void stop() {
		running = false;
		this.scheduleCommand(new Runnable() {
			@Override
			public void run() {
				unsubscribe();
				disconnect();
			}
		});
	}

	@Override
	public boolean isRunning() {
		return running;
	}

	@Override
	public boolean isConnected() {
		return connected;
	}

	@Override
	public String getStateInfo() {
		return state.toString();
	}

	@Override
	public DBRTimeEvent getDBRTimeEvent() {
		return this.dbrtimeevent;
	}

	@Override
	public ArchDBRTypes getArchDBRTypes() {
		return archDBRTypes;
	}

	@Override
	public void markPVHasMetafields(boolean hasMetaField) {
		if (hasMetaField) {
			allarchiveFieldsData = new ConcurrentHashMap<String, String>();
			changedarchiveFieldsData = new ConcurrentHashMap<String, String>();
		}
		this.hasMetaField = hasMetaField;
	}

	@Override
	public void setMetaFieldParentPV(PV parentPV, boolean isRuntimeOnly) {
		this.parentPVForMetaField = parentPV;
		this.isarchiveFieldsField = true;
		this.isruntimeFieldField = isRuntimeOnly;
	}

	@Override
	public void updataMetaFieldValue(String pvName, String fieldValue) {
		String[] strs = pvName.split("\\.");
		String fieldName = strs[strs.length - 1];
		if(isruntimeFieldField) { 
			logger.debug("Not storing value change for runtime field " + fieldName);
			runTimeFieldsData.put(fieldName, fieldValue);
		} else { 
			logger.debug("Storing value change for meta field " + fieldName);
			allarchiveFieldsData.put(fieldName, fieldValue);
			changedarchiveFieldsData.put(fieldName, fieldValue);
		}
	}

	@Override
	public HashMap<String, String> getLatestMetadata() {
		HashMap<String, String> retVal = new HashMap<String, String>();
		// The totalMetaInfo is updated once every 24hours...
		MetaInfo metaInfo = this.totalMetaInfo;
		if(metaInfo != null) {
			metaInfo.addToDict(retVal);
		}
		// Add the latest value of the fields we are monitoring.
		if(allarchiveFieldsData != null) { 
			retVal.putAll(allarchiveFieldsData);
		}
		if(runTimeFieldsData != null) { 
			retVal.putAll(runTimeFieldsData);
		}
		
		return retVal;
	}

	@Override
	public void updateTotalMetaInfo() throws IllegalStateException {
		// TODO cleanup this interface and implements this.
		throw new UnsupportedOperationException();
	}

	@Override
	public HashMap<String, String> getCurrentCopyOfMetaFields() {
		HashMap<String, String> retval = new HashMap<String, String>();
		if(totalMetaInfo != null && totalMetaInfo.getUnit() != null) { 
			retval.put("EGU", totalMetaInfo.getUnit());
			retval.put("PREC", Integer.toString(totalMetaInfo.getPrecision()));
		}
		
		if(allarchiveFieldsData != null && !allarchiveFieldsData.isEmpty()) { 
			retval.putAll(allarchiveFieldsData);
		}
		if(runTimeFieldsData != null && !runTimeFieldsData.isEmpty()) { 
			retval.putAll(runTimeFieldsData);
		}
		return retval;
	}

	@Override
	public String getHostName() {
		return hostName;
	}

	@Override
	public String getLowLevelChannelInfo() {
		return null;
	}
	
	@Override
	public String getRequesterName() {
		return this.getClass().getName() + "\tchannelName:" + this.name;
	}

	@Override
	public void message(String arg0, MessageType arg1) {
		logger.info(arg1);
	}

	@Override
	public void monitorConnect(Status status, Monitor channelMonitor, Structure structure) {
		if (monitorIsDestroyed)
			return;

		synchronized (this) {
			if (status.isSuccess()) {
				logger.debug("monitorConnect:" + "connect successfully");
				String structureID = structure.getID();
				logger.debug("Type from structure in monitorConnect is " + structureID);
				
				Field valueField = structure.getField("value");
				logger.debug("Value field in monitorConnect is of type " + valueField.getID());
				
				archDBRTypes = this.determineDBRType(structureID, valueField.getID());
				con = configservice.getArchiverTypeSystem().getV4Constructor(archDBRTypes);
				logger.debug("Determined ArchDBRTypes for " + this.name + " as " + archDBRTypes);

				channelMonitor.start();
				this.notify();
			} else {
				logger.debug("monitorConnect:" + "connect failed");
			}
		}
	}

	@Override
	public void monitorEvent(Monitor monitor) {
		MonitorElement monitorElement = null;
		try {
			if (monitorIsDestroyed)
				return;

			if (!running) {
				return;
			}

			if (subscription == null) {
				return;
			}

			state = PVConnectionState.GotMonitor;

			monitorElement = monitor.poll();

			if (monitorElement == null)
				return; // no monitors are present
			
			if(archDBRTypes == null || con == null) { 
				logger.error("Have not determined the DBRTYpes yet for " + this.name);
				return;
			}

			PVStructure totalPVStructure = monitorElement.getPVStructure();


			try { 
				dbrtimeevent = con.newInstance(totalPVStructure);
				totalMetaInfo.computeRate(dbrtimeevent.getDBRType(), System.currentTimeMillis(), dbrtimeevent, dbrtimeevent.getSampleValue().getElementCount());
			} catch (Exception e) {
				logger.error("exception in monitor changed function when converting DBR to dbrtimeevent", e);
			}


			if (!connected)
				connected = true;

			fireValueUpdate();

		} catch (final Exception ex) {
			logger.error("exception in monitor changed ", ex);
		} finally {
			monitor.release(monitorElement);
		}
	}

	@Override
	public void unlisten(Monitor monitor) {
		monitor.stop();
		monitor.destroy();
	}

	@Override
	public void channelCreated(Status status, Channel createdChannel) {
		logger.info("Channel has been created" + createdChannel.getChannelName() + " Status: " + status.toString());
	}

	@Override
	public void channelStateChange(final Channel channelChangingState, final org.epics.pvaccess.client.Channel.ConnectionState connectionStatus) {
		this.scheduleCommand(new Runnable() {
			@Override
			public void run() {
				if (connectionStatus == ConnectionState.CONNECTED) {
					logger.info("channelStateChange:connected " + channelChangingState.getChannelName());
					handleConnected(channelChangingState);
				} else if (connectionStatus == ConnectionState.DISCONNECTED) {
					logger.info("channelStateChange:disconnected " + channelChangingState.getChannelName());
					state = PVConnectionState.Disconnected;
					connected = false;
					unsubscribe();
					fireDisconnected();
				}
			}
		});
	}

	@Override
	public void channelGetConnect(final Status status, final ChannelGet channelGet, Structure arg2) {
		this.scheduleCommand(new Runnable() {
			@Override
			public void run() {
				if (status.isSuccess()) {
					channelGet.get();
				} else {
					System.err.println(status.getMessage());
				}
			}
		});
	}

	@Override
	public void getDone(final Status status, ChannelGet arg1, final PVStructure pvStructure, BitSet arg3) {
		this.scheduleCommand(new Runnable() {
			@Override
			public void run() {
				if (status.isSuccess()) {
					logger.info("Obtained  meta info for PV " + EPICS_V4_PV.this.name);
					totalMetaInfo.applyV4BasicInfo(EPICS_V4_PV.this.name, pvStructure, EPICS_V4_PV.this.configservice);
				}
			}
		});
	}
	
	private void scheduleCommand(final Runnable command) {
		configservice.getEngineContext().getJCACommandThread(jcaCommandThreadId).addCommand(command);
	}
	
	private void connect() {
		logger.info("pv connectting");
		this.scheduleCommand(new Runnable() {
			@Override
			public void run() {
				try {
					state = PVConnectionState.Connecting;
					synchronized (this) {
						if (channel == null) {
							channel = channelProvider.createChannel(name, EPICS_V4_PV.this, ChannelProvider.PRIORITY_DEFAULT);
						}

						if (channel == null)
							return;

						if (channel.getConnectionState() == ConnectionState.CONNECTED) {
							handleConnected(channel);
						}
					}
				} catch (Exception e) {
					logger.error("exception when connecting pv", e);
				}
			}
		});
	}



	/**
	 * PV is connected. Get meta info, or subscribe right away.
	 */
	private void handleConnected(final Channel channel) {
		if (state == PVConnectionState.Connected)
			return;

		state = PVConnectionState.Connected;

		for (final PVListener listener : listeners) {
			listener.pvConnected(this);
		}

		if (!running) {
			connected = true;
			synchronized (this) {
				this.notifyAll();
			}
			return;
		}

		PVStructure pvRequest = CreateRequest.create().createRequest("field(timeStamp,value,alarm)"); 
		channel.createChannelGet(this, pvRequest);
		subscribe();
	}

	private void disconnect() {
		Channel channel_copy;
		synchronized (this) {
			if (channel == null)
				return;
			channel_copy = channel;
			connected = false;
			channel = null;
		}

		try {
			channel_copy.destroy();
		} catch (final Throwable e) {
			logger.error("exception when disconnecting pv", e);
		}

		fireDisconnected();
	}


	/** Subscribe for value updates. */
	private void subscribe() {
		synchronized (this) {
			// Prevent multiple subscriptions.
			if (subscription != null) {
				return;
			}

			// Late callback, channel already closed?
			if (channel == null) {
				return;
			}

			try {
				state = PVConnectionState.Subscribing;
				totalMetaInfo.setStartTime(System.currentTimeMillis());
				PVStructure pvRequest = CreateRequest.create().createRequest("field(timeStamp,value,alarm)"); 
				subscription = channel.createMonitor(this, pvRequest);
			} catch (final Exception ex) {
				logger.error("exception when subscribing pv", ex);
			}
		}
	}



	/** Unsubscribe from value updates. */
	private void unsubscribe() {
		Monitor sub_copy;
		synchronized (this) {
			sub_copy = subscription;
			subscription = null;
			archDBRTypes = null;
			con = null;
		}

		if (sub_copy == null) {
			return;
		}

		try {
			sub_copy.stop();
			sub_copy.destroy();
		} catch (final Exception ex) {
			logger.error("exception when unsubscribing pv", ex);
		}
	}
	
	
	private ArchDBRTypes determineDBRType(String structureID, String valueTypeId) { 
		if(structureID == null || valueTypeId == null) { 
			return ArchDBRTypes.DBR_V4_GENERIC_BYTES;
		}

		if(structureID.contains("epics:nt/NTScalarArray") || structureID.contains("structure")) {
			switch(valueTypeId) { 
			case "string[]":
				return ArchDBRTypes.DBR_WAVEFORM_STRING;
			case "double[]":
				return ArchDBRTypes.DBR_WAVEFORM_DOUBLE;
			case "int[]":
				return ArchDBRTypes.DBR_WAVEFORM_INT;
			case "byte[]":
				return ArchDBRTypes.DBR_WAVEFORM_BYTE;
			case "float[]":
				return ArchDBRTypes.DBR_WAVEFORM_FLOAT;
			case "short[]":
				return ArchDBRTypes.DBR_WAVEFORM_SHORT;
			case "enum_t":
				return ArchDBRTypes.DBR_WAVEFORM_ENUM;
			default:
				logger.warn("Cannot determine arch dbrtypes for " + structureID + " and " + valueTypeId + " for PV " + this.name);
				return ArchDBRTypes.DBR_V4_GENERIC_BYTES;
			}
			} else {
				switch(valueTypeId) { 
				case "string":
					return ArchDBRTypes.DBR_SCALAR_STRING;
				case "double":
					return ArchDBRTypes.DBR_SCALAR_DOUBLE;
				case "int":
					return ArchDBRTypes.DBR_SCALAR_INT;
				case "byte":
					return ArchDBRTypes.DBR_SCALAR_BYTE;
				case "float":
					return ArchDBRTypes.DBR_SCALAR_FLOAT;
				case "short":
					return ArchDBRTypes.DBR_SCALAR_SHORT;
				case "enum_t":
					return ArchDBRTypes.DBR_SCALAR_ENUM;
				default:
					logger.warn("Cannot determine arch dbrtypes for " + structureID + " and " + valueTypeId + " for PV " + this.name);
					return ArchDBRTypes.DBR_V4_GENERIC_BYTES;
				}
			}
	}
	
	
    private static void init() {
        if (channelProvider == null) {
                org.epics.pvaccess.ClientFactory.start();
                logger.info("Registered the pvAccess client factory.");
                channelProvider = ChannelProviderRegistryFactory.getChannelProviderRegistry().getProvider(org.epics.pvaccess.ClientFactory.PROVIDER_NAME);

                for(String providerName : ChannelProviderRegistryFactory.getChannelProviderRegistry().getProviderNames()) {
                        logger.debug("PVAccess Channel provider " + providerName);
                }
        }
    }
}
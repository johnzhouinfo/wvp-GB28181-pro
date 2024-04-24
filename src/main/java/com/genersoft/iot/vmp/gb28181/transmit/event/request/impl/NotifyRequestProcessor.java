package com.genersoft.iot.vmp.gb28181.transmit.event.request.impl;

import com.genersoft.iot.vmp.conf.SipConfig;
import com.genersoft.iot.vmp.conf.UserSetting;
import com.genersoft.iot.vmp.gb28181.bean.*;
import com.genersoft.iot.vmp.gb28181.event.EventPublisher;
import com.genersoft.iot.vmp.gb28181.transmit.SIPProcessorObserver;
import com.genersoft.iot.vmp.gb28181.transmit.callback.DeferredResultHolder;
import com.genersoft.iot.vmp.gb28181.transmit.cmd.impl.SIPCommander;
import com.genersoft.iot.vmp.gb28181.transmit.event.request.ISIPRequestProcessor;
import com.genersoft.iot.vmp.gb28181.transmit.event.request.SIPRequestProcessorParent;
import com.genersoft.iot.vmp.gb28181.utils.NumericUtil;
import com.genersoft.iot.vmp.gb28181.utils.SipUtils;
import com.genersoft.iot.vmp.gb28181.utils.XmlUtil;
import com.genersoft.iot.vmp.service.IDeviceChannelService;
import com.genersoft.iot.vmp.storager.IRedisCatchStorage;
import com.genersoft.iot.vmp.storager.IVideoManagerStorage;
import com.genersoft.iot.vmp.utils.DateUtil;
import com.genersoft.iot.vmp.utils.redis.RedisUtil;
import gov.nist.javax.sip.message.SIPRequest;
import org.dom4j.DocumentException;
import org.dom4j.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import javax.sip.InvalidArgumentException;
import javax.sip.RequestEvent;
import javax.sip.SipException;
import javax.sip.header.FromHeader;
import javax.sip.message.Response;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * SIP命令类型： NOTIFY请求,这是作为上级发送订阅请求后，设备才会响应的
 */
@Component
public class NotifyRequestProcessor extends SIPRequestProcessorParent implements InitializingBean, ISIPRequestProcessor {


    private final static Logger logger = LoggerFactory.getLogger(NotifyRequestProcessor.class);

	@Autowired
	private UserSetting userSetting;

	@Autowired
	private IVideoManagerStorage storager;

	@Autowired
	private EventPublisher eventPublisher;

	@Autowired
	private SipConfig sipConfig;

	@Autowired
	private IRedisCatchStorage redisCatchStorage;

	@Autowired
	private EventPublisher publisher;

	private final String method = "NOTIFY";

	@Autowired
	private SIPProcessorObserver sipProcessorObserver;

	@Autowired
	private IDeviceChannelService deviceChannelService;

	@Autowired
	private NotifyRequestForCatalogProcessor notifyRequestForCatalogProcessor;

	@Autowired
	private NotifyRequestForMobilePositionProcessor notifyRequestForMobilePositionProcessor;

	private ConcurrentLinkedQueue<HandlerCatchData> taskQueue = new ConcurrentLinkedQueue<>();

	@Qualifier("taskExecutor")
	@Autowired
	private ThreadPoolTaskExecutor taskExecutor;

	private int maxQueueCount = 30000;

	@Override
	public void afterPropertiesSet() throws Exception {
		// 添加消息处理的订阅
		sipProcessorObserver.addRequestProcessor(method, this);
	}

	@Override
	public void process(RequestEvent evt) {
		try {
			if (taskQueue.size() >= userSetting.getMaxNotifyCountQueue()) {
				responseAck((SIPRequest) evt.getRequest(), Response.BUSY_HERE, null, null);
				logger.error("[notify] 待处理消息队列已满 {}，返回486 BUSY_HERE，消息不做处理", userSetting.getMaxNotifyCountQueue());
				return;
			} else {
				responseAck((SIPRequest) evt.getRequest(), Response.OK, null, null);
			}

		} catch (SipException | InvalidArgumentException | ParseException e) {
			logger.error("未处理的异常 ", e);
		}
		taskQueue.offer(new HandlerCatchData(evt, null, null));
	}
	@Scheduled(fixedRate = 200)   //每200毫秒执行一次
	public void executeTaskQueue(){
		if (taskQueue.isEmpty()) {
			return;
		}
		try {
			List<RequestEvent> catalogEventList = new ArrayList<>();
			List<RequestEvent> alarmEventList = new ArrayList<>();
			List<RequestEvent> mobilePositionEventList = new ArrayList<>();
			for (HandlerCatchData take : taskQueue) {
				if (take == null) {
					continue;
				}
				Element rootElement = getRootElement(take.getEvt());
				if (rootElement == null) {
					logger.error("处理NOTIFY消息时未获取到消息体,{}", take.getEvt().getRequest());
					continue;
				}
				String cmd = XmlUtil.getText(rootElement, "CmdType");

				if (CmdType.CATALOG.equals(cmd)) {
					catalogEventList.add(take.getEvt());
				} else if (CmdType.ALARM.equals(cmd)) {
					alarmEventList.add(take.getEvt());
				} else if (CmdType.MOBILE_POSITION.equals(cmd)) {
					mobilePositionEventList.add(take.getEvt());
				} else {
					logger.info("接收到消息：" + cmd);
				}
			}
			taskQueue.clear();
			if (!alarmEventList.isEmpty()) {
				processNotifyAlarm(alarmEventList);
			}
			if (!catalogEventList.isEmpty()) {
				notifyRequestForCatalogProcessor.process(catalogEventList);
			}
			if (!mobilePositionEventList.isEmpty()) {
				notifyRequestForMobilePositionProcessor.process(mobilePositionEventList);
			}
		} catch (DocumentException e) {
			logger.error("处理NOTIFY消息时错误", e);
		}
	}

	/***
	 * 处理alarm设备报警Notify
	 */
	private void processNotifyAlarm(List<RequestEvent> evtList) {
		if (!sipConfig.isAlarm()) {
			return;
		}
		if (!evtList.isEmpty()) {
			for (RequestEvent evt : evtList) {
				try {
					FromHeader fromHeader = (FromHeader) evt.getRequest().getHeader(FromHeader.NAME);
					String deviceId = SipUtils.getUserIdFromFromHeader(fromHeader);

					Element rootElement = getRootElement(evt);
					if (rootElement == null) {
						logger.error("处理alarm设备报警Notify时未获取到消息体{}", evt.getRequest());
						return;
					}
					Element deviceIdElement = rootElement.element("DeviceID");
					String channelId = deviceIdElement.getText().toString();

					Device device = redisCatchStorage.getDevice(deviceId);
					if (device == null) {
						logger.warn("[ NotifyAlarm ] 未找到设备：{}", deviceId);
						return;
					}
					rootElement = getRootElement(evt, device.getCharset());
					if (rootElement == null) {
						logger.warn("[ NotifyAlarm ] content cannot be null, {}", evt.getRequest());
						return;
					}
					DeviceAlarm deviceAlarm = new DeviceAlarm();
					deviceAlarm.setDeviceId(deviceId);
					deviceAlarm.setAlarmPriority(XmlUtil.getText(rootElement, "AlarmPriority"));
					deviceAlarm.setAlarmMethod(XmlUtil.getText(rootElement, "AlarmMethod"));
					String alarmTime = XmlUtil.getText(rootElement, "AlarmTime");
					if (alarmTime == null) {
						logger.warn("[ NotifyAlarm ] AlarmTime cannot be null");
						return;
					}
					deviceAlarm.setAlarmTime(DateUtil.ISO8601Toyyyy_MM_dd_HH_mm_ss(alarmTime));
					if (XmlUtil.getText(rootElement, "AlarmDescription") == null) {
						deviceAlarm.setAlarmDescription("");
					} else {
						deviceAlarm.setAlarmDescription(XmlUtil.getText(rootElement, "AlarmDescription"));
					}
					if (NumericUtil.isDouble(XmlUtil.getText(rootElement, "Longitude"))) {
						deviceAlarm.setLongitude(Double.parseDouble(XmlUtil.getText(rootElement, "Longitude")));
					} else {
						deviceAlarm.setLongitude(0.00);
					}
					if (NumericUtil.isDouble(XmlUtil.getText(rootElement, "Latitude"))) {
						deviceAlarm.setLatitude(Double.parseDouble(XmlUtil.getText(rootElement, "Latitude")));
					} else {
						deviceAlarm.setLatitude(0.00);
					}
					logger.info("[收到Notify-Alarm]：{}/{}", device.getDeviceId(), deviceAlarm.getChannelId());
					if ("4".equals(deviceAlarm.getAlarmMethod())) {
						MobilePosition mobilePosition = new MobilePosition();
						mobilePosition.setChannelId(channelId);
						mobilePosition.setCreateTime(DateUtil.getNow());
						mobilePosition.setDeviceId(deviceAlarm.getDeviceId());
						mobilePosition.setTime(deviceAlarm.getAlarmTime());
						mobilePosition.setLongitude(deviceAlarm.getLongitude());
						mobilePosition.setLatitude(deviceAlarm.getLatitude());
						mobilePosition.setReportSource("GPS Alarm");

						// 更新device channel 的经纬度
						DeviceChannel deviceChannel = new DeviceChannel();
						deviceChannel.setDeviceId(device.getDeviceId());
						deviceChannel.setChannelId(channelId);
						deviceChannel.setLongitude(mobilePosition.getLongitude());
						deviceChannel.setLatitude(mobilePosition.getLatitude());
						deviceChannel.setGpsTime(mobilePosition.getTime());

						deviceChannel = deviceChannelService.updateGps(deviceChannel, device);

						mobilePosition.setLongitudeWgs84(deviceChannel.getLongitudeWgs84());
						mobilePosition.setLatitudeWgs84(deviceChannel.getLatitudeWgs84());
						mobilePosition.setLongitudeGcj02(deviceChannel.getLongitudeGcj02());
						mobilePosition.setLatitudeGcj02(deviceChannel.getLatitudeGcj02());

						deviceChannelService.updateChannelGPS(device, deviceChannel, mobilePosition);
					}

					// 回复200 OK
					if (redisCatchStorage.deviceIsOnline(deviceId)) {
						publisher.deviceAlarmEventPublish(deviceAlarm);
					}
				} catch (DocumentException e) {
					logger.error("未处理的异常 ", e);
				}
			}
		}
	}

	public void setCmder(SIPCommander cmder) {
	}

	public void setStorager(IVideoManagerStorage storager) {
		this.storager = storager;
	}

	public void setPublisher(EventPublisher publisher) {
		this.publisher = publisher;
	}

	public void setRedis(RedisUtil redis) {
	}

	public void setDeferredResultHolder(DeferredResultHolder deferredResultHolder) {
	}

	public IRedisCatchStorage getRedisCatchStorage() {
		return redisCatchStorage;
	}

	public void setRedisCatchStorage(IRedisCatchStorage redisCatchStorage) {
		this.redisCatchStorage = redisCatchStorage;
	}

	@Scheduled(fixedRate = 10000)   //每1秒执行一次
	public void execute(){
		logger.info("[待处理Notify消息数量]: {}", taskQueue.size());
	}
}

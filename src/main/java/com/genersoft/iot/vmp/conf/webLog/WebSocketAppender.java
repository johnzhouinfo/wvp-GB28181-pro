package com.genersoft.iot.vmp.conf.webLog;

import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.nio.charset.StandardCharsets;

@Data
@EqualsAndHashCode(callSuper = true)
public class WebSocketAppender  extends AppenderBase<ILoggingEvent> {

    private PatternLayoutEncoder encoder;

    @Override
    protected void append(ILoggingEvent loggingEvent) {
        byte[] data = this.encoder.encode(loggingEvent);
        // Push to client.
//        LogChannel.push(DateUtil.timestampMsTo_yyyy_MM_dd_HH_mm_ss(loggingEvent.getTimeStamp()) + " " +  loggingEvent.getFormattedMessage());
        LogChannel.push(new String(data, StandardCharsets.UTF_8));
    }
}

package org.yamcs.simulator;

import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Date;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yamcs.cfdp.pdu.CfdpHeader;
import org.yamcs.cfdp.pdu.CfdpPacket;
import org.yamcs.tctm.ErrorDetectionWordCalculator;
import org.yamcs.tctm.ccsds.error.CrcCciitCalculator;
import org.yamcs.utils.ByteArrayUtils;

/**
 * Simulator working with Columbus/ISS kind of packet structure
 *
 * @author nm
 *
 */
public class ColSimulator extends AbstractSimulator {

    private static final Logger log = LoggerFactory.getLogger(ColSimulator.class);

    // no more than 100 pending commands
    protected BlockingQueue<ColumbusCcsdsPacket> pendingCommands = new ArrayBlockingQueue<>(100);

    static int DEFAULT_MAX_LENGTH = 65542;
    int maxLength = DEFAULT_MAX_LENGTH;
    private TcpTmTcLink tmLink;
    private TcpTmTcLink tm2Link;
    private TcpTmTcLink losLink;
    private UdpTmFrameLink tmFrameLink;

    private boolean los;
    private Date lastLosStart;
    private Date lastLosStop;
    private LosRecorder losRecorder;

    FlightDataHandler flightDataHandler;
    DHSHandler dhsHandler;
    PowerHandler powerDataHandler;
    RCSHandler rcsHandler;
    EpsLvpduHandler epslvpduHandler;

    int tmCycle = 0;
    AtomicInteger tm2SeqCount = new AtomicInteger(0);
    ErrorDetectionWordCalculator edwc2 = new CrcCciitCalculator();

    ScheduledThreadPoolExecutor executor;

    static final int CFDP_APID = 2045;
    static final int MAIN_APID = 1;
    static final int PERF_TEST_APID = 2;
    static final int TC_ACK_APID = 101;

    CfdpReceiver cfdpReceiver;

    public ColSimulator(File dataDir) {
        losRecorder = new LosRecorder(dataDir);
        powerDataHandler = new PowerHandler();
        rcsHandler = new RCSHandler();
        epslvpduHandler = new EpsLvpduHandler();
        flightDataHandler = new FlightDataHandler();
        dhsHandler = new DHSHandler();
        cfdpReceiver = new CfdpReceiver(this);
    }

    /**
     * this runs in a separate thread but pushes commands to the main TM thread
     */
    public LosRecorder getLosDataRecorder() {
        return losRecorder;
    }

    public boolean isLOS() {
        return los;
    }

    public Date getLastLosStart() {
        return lastLosStart;
    }

    public Date getLastLosStop() {
        return lastLosStop;
    }

    public void setAOS() {
        if (los) {
            los = false;
            lastLosStop = new Date();
            losRecorder.stopRecording();
        }
    }

    public void setLOS() {
        if (!los) {
            los = true;
            lastLosStart = new Date();
            losRecorder.startRecording(lastLosStart);
        }
    }

    public void transmitRealtimeTM(SimulatorCcsdsPacket packet) {
        packet.fillChecksum();
        if (isLOS()) {
            losRecorder.record(packet);
        } else {
            tmLink.sendPacket(packet.getBytes());
            if (tmFrameLink != null) {
                tmFrameLink.queuePacket(0, packet.getBytes());
            }

        }
    }

    protected void transmitTM2(byte[] packet) {
        if (!isLOS()) {
            tm2Link.sendPacket(packet);
            if (tmFrameLink != null) {
                tmFrameLink.queuePacket(1, encapsulate(packet));
            }
        }

    }

    // encapsulate packet
    byte[] encapsulate(byte[] p) {

        byte[] p1 = new byte[p.length + 4];
        System.arraycopy(p, 0, p1, 4, p.length);
        p1[0] = (byte) 0xFE;
        ByteArrayUtils.encodeShort(p1.length, p1, 2);
        return p1;
    }

    public void dumpLosDataFile(String filename) {
        // read data from los storage
        if (filename == null) {
            filename = losRecorder.getCurrentRecordingName();
            if (filename == null) {
                return;
            }
        }

        try (DataInputStream dataStream = new DataInputStream(losRecorder.getInputStream(filename))) {
            while (dataStream.available() > 0) {
                ColumbusCcsdsPacket packet = readLosPacket(dataStream);
                if (packet != null) {
                    losLink.sendPacket(packet.getBytes());
                    if (tmFrameLink != null) {
                        tmFrameLink.queuePacket(2, packet.getBytes());
                    }
                }
            }

            // add packet notifying that the file has been downloaded entirely
            ColumbusCcsdsPacket confirmationPacket = buildLosTransmittedRecordingPacket(filename);
            transmitRealtimeTM(confirmationPacket);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static ColumbusCcsdsPacket buildLosTransmittedRecordingPacket(String transmittedRecordName) {
        byte[] recName = transmittedRecordName.getBytes();
        ColumbusCcsdsPacket packet = new ColumbusCcsdsPacket(0, recName.length + 1, 10, false);
        packet.getUserDataBuffer().put(recName);

        return packet;
    }

    public void deleteLosDataFile(String filename) {
        losRecorder.deleteDump(filename);
        // add packet notifying that the file has been deleted
        ColumbusCcsdsPacket confirmationPacket = buildLosDeletedRecordingPacket(filename);
        transmitRealtimeTM(confirmationPacket);
    }

    private static ColumbusCcsdsPacket buildLosDeletedRecordingPacket(String deletedRecordName) {
        byte[] recName = deletedRecordName.getBytes();
        ColumbusCcsdsPacket packet = new ColumbusCcsdsPacket(0, recName.length + 1, 11, false);
        packet.getUserDataBuffer().put(recName);
        return packet;
    }

    protected ColumbusCcsdsPacket ackPacket(ColumbusCcsdsPacket commandPacket, int stage, int result) {
        ColumbusCcsdsPacket ackPacket = new ColumbusCcsdsPacket(TC_ACK_APID, 10, commandPacket.getPacketType(), 2000,
                false);
        int batNum = commandPacket.getPacketId();

        ByteBuffer bb = ackPacket.getUserDataBuffer();

        bb.putInt(0, batNum);
        bb.putInt(4, commandPacket.getSequenceCount());
        bb.put(8, (byte) stage);
        bb.put(9, (byte) result);

        return ackPacket;
    }

    private void sendFlightPacket() {
        ColumbusCcsdsPacket flightpacket = new ColumbusCcsdsPacket(MAIN_APID, flightDataHandler.dataSize(), 33);
        flightDataHandler.fillPacket(flightpacket.getUserDataBuffer());
        transmitRealtimeTM(flightpacket);
    }

    private void sendCfdp() {
        // byte[] filedata = { 'T', 'h', 'i', 's', ' ', 'i', 's', ' ', 'a', ' ', 't', 'e', 's', 't', '.' };
        // CfdpPacket cfdpFileData = new FileDataPacket(filedata, 0, FileDataPacket.createHeader(filedata));
        // transmitCfdp(cfdpFileData);
    }

    private void sendHkTm() {
        ColumbusCcsdsPacket powerpacket = new ColumbusCcsdsPacket(MAIN_APID, powerDataHandler.dataSize(), 1);
        powerDataHandler.fillPacket(powerpacket.getUserDataBuffer());
        transmitRealtimeTM(powerpacket);

        ColumbusCcsdsPacket packet = new ColumbusCcsdsPacket(MAIN_APID, dhsHandler.dataSize(), 2);
        dhsHandler.fillPacket(packet.getUserDataBuffer());
        transmitRealtimeTM(packet);

        packet = new ColumbusCcsdsPacket(MAIN_APID, rcsHandler.dataSize(), 3);
        rcsHandler.fillPacket(packet.getUserDataBuffer());
        transmitRealtimeTM(packet);

        packet = new ColumbusCcsdsPacket(MAIN_APID, epslvpduHandler.dataSize(), 4);
        epslvpduHandler.fillPacket(packet.getUserDataBuffer());
        transmitRealtimeTM(packet);
    }

    /**
     * creates and sends a dummy packet with the following structure
     * <ul>
     * <li>size (2 bytes)</li>
     * <li>unix timestamp in millisec(8 bytes)</li>
     * <li>seq count(4 bytes)</li>
     * <li>uint32</li>
     * <li>64 bit float</li>
     * <li>checksum (2 bytes)</li>
     * </ul>
     */
    private void sendTm2() {
        int n = 28;
        ByteBuffer bb = ByteBuffer.allocate(n);
        bb.putShort((short) (n - 2));
        bb.putLong(System.currentTimeMillis());
        int seq = tm2SeqCount.getAndIncrement();
        bb.putInt(seq);
        bb.putInt(seq + 1000);
        bb.putDouble(Math.sin(seq / 10.0));
        bb.putShort((short) edwc2.compute(bb.array(), 0, n - 2));
        transmitTM2(bb.array());
    }

    /**
     * runs in the main TM thread, executes commands from the queue (if any)
     */
    private void executePendingCommands() {
        ColumbusCcsdsPacket commandPacket;
        while ((commandPacket = pendingCommands.poll()) != null) {
            if (commandPacket.getPacketType() == 10) {
                log.info("Received TC packet-id: " + commandPacket.getPacketId());

                switch (commandPacket.getPacketId()) {
                case 1:
                    switchBatteryOn(commandPacket);
                    break;
                case 2:
                    switchBatteryOff(commandPacket);
                    break;
                case 3:
                    criticalTc1(commandPacket);
                    break;
                case 4:
                    criticalTc2(commandPacket);
                    break;
                case 5:
                    listRecordings(commandPacket);
                    break;
                case 6:
                    dumpRecording(commandPacket);
                    break;
                case 7:
                    deleteRecording(commandPacket);
                    break;

                default:
                    log.error("Invalid command packet id: {}", commandPacket.getPacketId());
                }
            } else {
                log.warn("Unknown command type " + commandPacket.getPacketType());
            }
        }
    }

    private void switchBatteryOn(ColumbusCcsdsPacket commandPacket) {
        transmitRealtimeTM(ackPacket(commandPacket, 1, 0));
        commandPacket.setPacketId(1);
        int batNum = commandPacket.getUserDataBuffer().get(0);
        executor.schedule(() -> powerDataHandler.setBatteryOn(batNum), 500, TimeUnit.MILLISECONDS);
        transmitRealtimeTM(ackPacket(commandPacket, 2, 0));
    }

    private void switchBatteryOff(ColumbusCcsdsPacket commandPacket) {
        transmitRealtimeTM(ackPacket(commandPacket, 1, 0));
        commandPacket.setPacketId(2);
        int batNum = commandPacket.getUserDataBuffer().get(0);
        executor.schedule(() -> powerDataHandler.setBatteryOff(batNum), 500, TimeUnit.MILLISECONDS);
        transmitRealtimeTM(ackPacket(commandPacket, 2, 0));
    }

    private void listRecordings(ColumbusCcsdsPacket commandPacket) {
        transmitRealtimeTM(ackPacket(commandPacket, 1, 0));
        String[] dumps = losRecorder.listRecordings();

        log.info("LOS dump count: {}", dumps.length);
        String joined = String.join(" ", dumps);
        byte[] b = joined.getBytes();

        ColumbusCcsdsPacket packet = new ColumbusCcsdsPacket(0, b.length + 1, 9, false);
        packet.getUserDataBuffer().put(b);

        transmitRealtimeTM(packet);
        transmitRealtimeTM(ackPacket(commandPacket, 2, 0));
    }

    private void dumpRecording(ColumbusCcsdsPacket commandPacket) {
        transmitRealtimeTM(ackPacket(commandPacket, 1, 0));
        byte[] fileNameArray = commandPacket.getUserDataBuffer().array();
        int indexStartOfString = 16;
        int indexEndOfString = indexStartOfString;
        for (int i = indexStartOfString; i < fileNameArray.length; i++) {
            if (fileNameArray[i] == 0) {
                indexEndOfString = i;
                break;
            }
        }
        String fileName1 = new String(fileNameArray, indexStartOfString, indexEndOfString - indexStartOfString);
        log.info("Command DUMP_RECORDING for file {}", fileName1);
        dumpLosDataFile(fileName1);
        transmitRealtimeTM(ackPacket(commandPacket, 2, 0));
    }

    private void deleteRecording(ColumbusCcsdsPacket commandPacket) {
        transmitRealtimeTM(ackPacket(commandPacket, 1, 0));
        byte[] fileNameArray = commandPacket.getUserDataBuffer().array();
        String fileName = new String(fileNameArray, 16, fileNameArray.length - 22);
        log.info("Command DELETE_RECORDING for file {}", fileName);
        deleteLosDataFile(fileName);
        transmitRealtimeTM(ackPacket(commandPacket, 2, 0));
    }

    private void criticalTc1(ColumbusCcsdsPacket commandPacket) {
        transmitRealtimeTM(ackPacket(commandPacket, 1, 0));
        log.info("Command CRITICAL_TC1");
        transmitRealtimeTM(ackPacket(commandPacket, 2, 0));
    }

    private void criticalTc2(ColumbusCcsdsPacket commandPacket) {
        transmitRealtimeTM(ackPacket(commandPacket, 1, 0));
        log.info("Command CRITICAL_TC2");
        transmitRealtimeTM(ackPacket(commandPacket, 2, 0));
    }

    public void setTmLink(TcpTmTcLink tmLink) {
        this.tmLink = tmLink;
    }

    public void setTm2Link(TcpTmTcLink tm2Link) {
        this.tm2Link = tm2Link;
    }

    public void processTc(SimulatorCcsdsPacket tc) {
        ColumbusCcsdsPacket coltc = (ColumbusCcsdsPacket) tc;
        if (tc.getAPID() == CFDP_APID) {
            cfdpReceiver.processCfdp(tc.getUserDataBuffer());
        } else {
            transmitRealtimeTM(ackPacket(coltc, 0, 0));
            try {
                pendingCommands.put(coltc);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    protected ColumbusCcsdsPacket readLosPacket(DataInputStream dIn) {
        try {
            byte hdr[] = new byte[6];
            dIn.readFully(hdr);
            int remaining = ((hdr[4] & 0xFF) << 8) + (hdr[5] & 0xFF) + 1;
            if (remaining > maxLength - 6) {
                throw new IOException(
                        "Remaining packet length too big: " + remaining + " maximum allowed is " + (maxLength - 6));
            }
            byte[] b = new byte[6 + remaining];
            System.arraycopy(hdr, 0, b, 0, 6);
            dIn.readFully(b, 6, remaining);
            return new ColumbusCcsdsPacket(ByteBuffer.wrap(b));
        } catch (Exception e) {
            log.error("Error reading LOS packet from file " + e.getMessage(), e);
        }
        return null;
    }

    public void setLosLink(TcpTmTcLink losLink) {
        this.losLink = losLink;
    }

    public void setTmFrameLink(UdpTmFrameLink tmFrameLink) {
        this.tmFrameLink = tmFrameLink;
    }

    public void setTcFrameLink(UdpTcFrameLink tcFrameLink) {
        // nothing to do with the link, we get called in new command
    }

    @Override
    protected void doStart() {
        executor = new ScheduledThreadPoolExecutor(1);
        executor.scheduleAtFixedRate(() -> sendFlightPacket(), 0, 200, TimeUnit.MILLISECONDS);
        executor.scheduleAtFixedRate(() -> sendHkTm(), 0, 1000, TimeUnit.MILLISECONDS);
        executor.scheduleAtFixedRate(() -> sendTm2(), 0, 1000, TimeUnit.MILLISECONDS);
        executor.scheduleAtFixedRate(() -> sendCfdp(), 0, 1000, TimeUnit.MILLISECONDS);
        executor.scheduleAtFixedRate(() -> executePendingCommands(), 0, 200, TimeUnit.MILLISECONDS);

        notifyStarted();
    }

    @Override
    protected void doStop() {
        executor.shutdownNow();
        notifyStopped();
    }

    @Override
    public void transmitCfdp(CfdpPacket packet) {
        CfdpHeader header = packet.getHeader();

        int length = 16 + header.getLength() + packet.getDataFieldLength();
        ByteBuffer buffer = ByteBuffer.allocate(length);
        buffer.putShort((short) 0x17FD);
        buffer.putShort(4, (short) (length - 7));
        buffer.position(16);
        packet.writeToBuffer(buffer.slice());
        transmitRealtimeTM(new ColumbusCcsdsPacket(buffer));
    }
}
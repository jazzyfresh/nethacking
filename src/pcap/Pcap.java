package pcap;

import org.pcap4j.core.*;

import java.io.Closeable;
import java.io.IOException;
import java.net.*;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

public class Pcap {
    static {
        _muteSlf4j();
    }

    private static final int SNAPLEN        = 65536;    // bytes
    private static final int READ_TIMEOUT   = 10;       // ms
    private static final int COUNT          = 1;

    public static Closeable listen(String iface, Listener l) {
        return listen(iface, null, false, l);
    }
    public static Closeable listen(String iface, String filter, Listener l) {
        return listen(iface, filter, false, l);
    }
    public static Closeable listen(String iface, String filter, boolean rfmon, Listener l) {
        PcapNetworkInterface nif = get(iface);

        PcapHandle _recv = null;
        ExecutorService _pool = null;
        ExecutorService _loop = null;

        try {
            PcapHandle.Builder phb
                    = new PcapHandle.Builder(nif.getName())
                    .snaplen(SNAPLEN)
                    .promiscuousMode(PcapNetworkInterface.PromiscuousMode.PROMISCUOUS)
                    .timeoutMillis(READ_TIMEOUT);

            if (rfmon)
                phb.rfmon(true);

            PcapHandle recv = phb.build();

            _recv = recv;

            if (filter != null && !filter.equals(""))
                recv.setFilter(filter, BpfProgram.BpfCompileMode.OPTIMIZE);

            ExecutorService pool = Executors.newSingleThreadExecutor();
            ExecutorService loop = Executors.newCachedThreadPool();
            _pool = pool;
            _loop = loop;

            // pump events
            pool.execute(() -> {
                try {
                    while (!pool.isShutdown() && recv.isOpen()) {
                        recv.loop(COUNT, l::onPacket);
                    }
                } catch (PcapNativeException e) {
                    throw new RuntimeException(e);
                } catch (NotOpenException | InterruptedException e) {
                    // do nothing
                }
            });
            return () -> _close(recv, pool, loop);
        } catch (Exception e) {
            _close(_recv, _pool, _loop);
            throw new RuntimeException(e);
        }
    }

    private static void _close(PcapHandle _recv, ExecutorService _pool, ExecutorService _loop) {
        if (_pool != null) _pool.shutdown();
        if (_loop != null) _loop.shutdown();
        if (_recv != null && _recv.isOpen()) {
            try {
                _recv.breakLoop();
                Threads.sleep(1000);
            } catch (NotOpenException e) {
                // nothing
            }
            _recv.close();
        }
    }

    public static void send(String iface, byte[] bytes) {
        send(get(iface), bytes);
    }
    public static void send(PcapNetworkInterface nif, byte[] bytes) {
        PcapHandle send = null;
        try {
            send = nif.openLive(SNAPLEN, PcapNetworkInterface.PromiscuousMode.PROMISCUOUS, READ_TIMEOUT);
            send.sendPacket(bytes);
        } catch (PcapNativeException | NotOpenException e) {
            throw new RuntimeException(e);
        } finally {
            if (send != null && send.isOpen()) send.close();
        }

    }

    public static List<PcapNetworkInterface> interfaces() {
        try {
            return Pcaps.findAllDevs();
        } catch (PcapNativeException e) {
            throw new RuntimeException(e);
        }
    }
    public static PcapNetworkInterface get(String iface)  {
        try {
            for (PcapNetworkInterface dev : Pcaps.findAllDevs())
                if (iface.equals(dev.getName()))
                    return dev;

            throw new IllegalArgumentException("Can't find interface with name: " + iface +
                    ". Available interfaces are: " +
                    interfaces().stream().map(PcapNetworkInterface::getName).collect(Collectors.toList()));
        } catch (PcapNativeException e) {
            throw new RuntimeException(e);
        }
    }

    public static PcapNetworkInterface getDefault() {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("www.google.com", 80), 3000);
            s.getOutputStream().write(new byte[]{1, 2, 3});

            return get(NetworkInterface.getByInetAddress(s.getLocalAddress()).getDisplayName());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public interface Listener {
        void onPacket(byte[] bytes);
    }

    public static void _muteSlf4j() {
        // override by adding "java -Dorg.slf4j.simpleLogger.defaultLogLevel=INFO"
        if (!System.getProperties().containsKey(org.slf4j.impl.SimpleLogger.DEFAULT_LOG_LEVEL_KEY)) {
            System.setProperty(org.slf4j.impl.SimpleLogger.DEFAULT_LOG_LEVEL_KEY, "ERROR");
        }
    }

}

package es.adeodato.dnsguardian.vpn;

import android.net.VpnService;
import android.util.Log;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.UnknownHostException;

import javax.net.SocketFactory;

/**
 * SocketFactory que llama a VpnService.protect() en cada socket nuevo,
 * antes de que OkHttp lo conecte o lo envuelva con TLS.
 *
 * Por qué protect() devolvía false en el primer intento:
 * ────────────────────────────────────────────────────────
 * En Android, «new Socket()» no crea el file descriptor real hasta que se
 * llama a bind() o connect(). Si protect() se invoca sobre un Socket sin fd,
 * el sistema no tiene nada que marcar → devuelve false.
 *
 * Fix: en createSocket() (sin args, la única variante que usa OkHttp 4.x)
 * llamamos a socket.bind(new InetSocketAddress(0)) antes de protect().
 * bind() con puerto 0 le pide al SO un puerto efímero en cualquier interfaz;
 * esto crea el fd real sin enviar ningún byte por la red.
 * Después protect() encuentra un fd válido y devuelve true.
 *
 * OkHttp llama a connect() a continuación; como el socket ya tiene fd y
 * está protegido, la conexión TCP sale por la red física, no por el TUN.
 */
public class ProtectedSocketFactory extends SocketFactory {

    private static final String TAG = "ProtectedSocketFactory";

    private final VpnService vpn;
    private final SocketFactory base = SocketFactory.getDefault();

    public ProtectedSocketFactory(VpnService vpn) {
        this.vpn = vpn;
    }

    private Socket guard(Socket s) throws IOException {
        boolean ok = vpn.protect(s);
        Log.d(TAG, "protect(" + s + ") → " + ok);
        if (!ok) throw new IOException("VpnService.protect() devolvió false");
        return s;
    }

    /**
     * Variante sin args: la única que OkHttp 4.x usa para conexiones directas.
     * bind(0) fuerza la creación del fd antes de que protect() lo necesite.
     */
    @Override
    public Socket createSocket() throws IOException {
        Socket s = new Socket();
        s.bind(new InetSocketAddress(0));   // ← crea el fd real en el SO
        return guard(s);
    }

    @Override
    public Socket createSocket(String host, int port)
            throws IOException, UnknownHostException {
        return guard(base.createSocket(host, port));
    }

    @Override
    public Socket createSocket(String host, int port,
                               InetAddress localHost, int localPort)
            throws IOException, UnknownHostException {
        return guard(base.createSocket(host, port, localHost, localPort));
    }

    @Override
    public Socket createSocket(InetAddress host, int port)
            throws IOException {
        return guard(base.createSocket(host, port));
    }

    @Override
    public Socket createSocket(InetAddress address, int port,
                               InetAddress localAddress, int localPort)
            throws IOException {
        return guard(base.createSocket(address, port, localAddress, localPort));
    }
}

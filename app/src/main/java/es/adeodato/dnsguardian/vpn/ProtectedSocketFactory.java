package es.adeodato.dnsguardian.vpn;

import android.net.VpnService;
import android.util.Log;

import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.net.UnknownHostException;

import javax.net.SocketFactory;

/**
 * SocketFactory que llama a VpnService.protect() en cada socket nuevo,
 * antes de que OkHttp lo conecte o lo envuelva con TLS.
 *
 * Por qué es necesario:
 * En algunos dispositivos (Samsung One UI, MIUI…) la VPN enruta también
 * el tráfico del propio proceso VpnService, aunque solo hayamos añadido
 * rutas selectivas. protect() marca el fd del socket para que el SO
 * lo excluya de la tabla de rutas VPN y lo saque por la red física.
 *
 * El socket SSL que OkHttp crea encima comparte el mismo fd, por lo que
 * la protección se mantiene en toda la conexión HTTPS.
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

    // OkHttp usa esta variante (sin args) para crear el socket base
    // antes de conectarlo. Es el punto más importante donde protect().
    @Override
    public Socket createSocket() throws IOException {
        return guard(new Socket());
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

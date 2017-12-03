package com.amaze.filemanager.services.ssh;

import android.net.Uri;
import android.support.annotation.NonNull;
import android.util.Log;

import com.amaze.filemanager.database.UtilsHandler;
import com.amaze.filemanager.utils.AppConfig;

import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.common.KeyType;
import net.schmizz.sshj.userauth.keyprovider.KeyProvider;

import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class SshConnectionPool
{
    public static final int SSH_DEFAULT_PORT = 22;

    public static final String SSH_URI_PREFIX = "ssh://";

    private static final String TAG = "SshConnectionPool";

    private static SshConnectionPool sInstance = null;

    private final Map<String, SSHClient> mConnections;

    private SshConnectionPool()
    {
        mConnections = new ConcurrentHashMap<String, SSHClient>();
    }

    public static final SshConnectionPool getInstance() {
        if(sInstance == null)
            sInstance = new SshConnectionPool();

        return sInstance;
    }

    public SSHClient getConnection(@NonNull String url) throws IOException {
        url = SshClientUtils.extractBaseUriFrom(url);
        Log.d(TAG, "Opening connection for " + url);

        SSHClient client = mConnections.get(url);
        if(client == null) {
            client = create(url);
        } else {
            if(!validate(client)) {
                Log.d(TAG, "Connection no longer usable. Reconnecting...");
                expire(client);
                mConnections.remove(url);
                client = create(url);
            }
        }
        mConnections.put(url, client);
        return client;
    }

    private SSHClient create(@NonNull String url) throws IOException {
        return create(Uri.parse(url));
    }

    private SSHClient create(@NonNull Uri uri) throws IOException {
        String host = uri.getHost();
        int port = uri.getPort();
        //If the uri is fetched from the app's database storage, we assume it will never be empty
        String[] userInfo = uri.getUserInfo().split(":");
        String username = userInfo[0];
        String password = userInfo.length > 1 ? userInfo[1] : null;

        if(port < 0)
            port = SSH_DEFAULT_PORT;

        SSHClient client = new SSHClient(new CustomSshJConfig());
        UtilsHandler utilsHandler = AppConfig.getInstance().getUtilsHandler();
        client.addHostKeyVerifier(utilsHandler.getSshHostKey(uri.toString()));
        client.connect(host, port);
        if(password != null)
            client.authPassword(username, password);
        else
            client.authPublickey(username, createKeyProviderFrom(utilsHandler.getSshAuthPrivateKey(uri.toString())));
        return client;
    }

    private boolean validate(@NonNull SSHClient client) {
        return client.isConnected() && client.isAuthenticated();
    }

    private void expire(@NonNull SSHClient client) {
        SshClientUtils.tryDisconnect(client);
    }

    public void expungeAllConnections() {
        if(!mConnections.isEmpty()) {
            for(SSHClient connection : mConnections.values()) {
                SshClientUtils.tryDisconnect(connection);
            }
            mConnections.clear();
        }
    }

    private KeyProvider createKeyProviderFrom(@NonNull String pemContents) throws IOException {
        Reader reader = new StringReader(pemContents);
        PEMParser pemParser = new PEMParser(reader);

        PEMKeyPair keyPair = (PEMKeyPair) pemParser.readObject();
        JcaPEMKeyConverter converter = new JcaPEMKeyConverter();
        final KeyPair retval = converter.getKeyPair(keyPair);

        return new KeyProvider() {
            @Override
            public PrivateKey getPrivate() throws IOException {
                return retval.getPrivate();
            }

            @Override
            public PublicKey getPublic() throws IOException {
                return retval.getPublic();
            }

            @Override
            public KeyType getType() throws IOException {
                return KeyType.fromKey(getPublic());
            }
        };
    }
}

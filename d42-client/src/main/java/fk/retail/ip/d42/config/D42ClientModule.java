package fk.retail.ip.d42.config;

import com.google.inject.AbstractModule;
import fk.retail.ip.d42.client.D42Client;
import fk.retail.ip.d42.client.D42ClientImpl;

/**
 * Created by harshul.jain on 24/04/17.
 */
public class D42ClientModule extends AbstractModule{
    @Override
    protected void configure() {
        D42Client d42Client = new D42ClientImpl("EKBK5XNIHLM7E3290RBX", "QGXi9BkrIArIb255tHRFR7trEcPSvxBi3X0LsBwP", "http://10.47.2.2");
        bind(D42Client.class).toInstance(d42Client);
    }
}

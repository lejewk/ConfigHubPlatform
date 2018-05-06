package com.confighub.api.system.conf.ldap;

import com.confighub.api.server.auth.TokenState;
import com.confighub.api.system.ASysAdminAccessValidation;
import com.confighub.core.auth.LdapConnector;
import com.confighub.core.auth.LdapEntry;
import com.confighub.core.auth.TrustAllX509TrustManager;
import com.confighub.core.store.Store;
import com.confighub.core.system.SystemConfig;
import com.confighub.core.system.conf.LdapConfig;
import com.confighub.core.system.conf.LdapTestConfig;
import com.confighub.core.user.UserAccount;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.apache.directory.api.ldap.model.cursor.CursorException;
import org.apache.directory.api.ldap.model.exception.LdapException;
import org.apache.directory.ldap.client.api.LdapConnectionConfig;
import org.apache.directory.ldap.client.api.LdapNetworkConnection;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.io.IOException;
import java.net.URI;

@Path("/getLDAPConfig")
public class GetLDAPConfig
        extends ASysAdminAccessValidation
{
    private static final Logger log = LogManager.getLogger(GetLDAPConfig.class);

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public LdapConfig create(@HeaderParam("Authorization") final String token)
    {
        Store store = new Store();

        try
        {
            final UserAccount user = TokenState.getUser(token, store);
            LdapConfig conf = LdapConfig.build(store.getSystemConfig(SystemConfig.ConfigGroup.LDAP));
            return conf;
        }
        finally
        {
            store.close();
        }
    }

    @POST
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    @Path("/testLdap")
    public Response testLdapConfiguration(final LdapTestConfig ldapConfig,
                                          @HeaderParam("Authorization") final String token)
    {
        Gson gson = new Gson();
        Store store = new Store();
        try
        {
            int status = validateCHAdmin(token, store);
            if (0 != status)
                return Response.status(status).build();
        }
        finally
        {
            store.close();
        }

        final LdapConnectionConfig config = new LdapConnectionConfig();
        final URI ldapUri = URI.create(ldapConfig.getLdapUrl());
        config.setLdapHost(ldapUri.getHost());
        config.setLdapPort(ldapUri.getPort());
        config.setUseSsl(ldapUri.getScheme().startsWith("ldaps"));
        config.setUseTls(false);

        if (ldapConfig.isTrustAllCertificates())
        {
            config.setTrustManagers(new TrustAllX509TrustManager());
        }

        config.setName(ldapConfig.getSystemUsername());
        config.setCredentials(ldapConfig.getSystemPassword());

        LdapNetworkConnection connection = null;
        try
        {
            LdapConnector ldapConnector = new LdapConnector();

            try
            {
                connection = ldapConnector.connect(config);
            }
            catch (LdapException e)
            {
                log.error("Failed to connect to LDAP: " + e.getMessage());

                JsonObject json = new JsonObject();
                json.addProperty("success", false);
                JsonObject response = new JsonObject();
                response.addProperty("connected", false);
                response.addProperty("authenticated", false);
                json.add("errorMessage", response);

                return Response.ok(gson.toJson(json), MediaType.APPLICATION_JSON).build();
            }

            boolean connected = null != connection && connection.isConnected();
            boolean systemAuthenticated = connection.isAuthenticated();

            // the web interface allows testing the connection only, in that case we can bail out early.
            if (null == connection || ldapConfig.isTestConnectionOnly())
            {
                JsonObject json = new JsonObject();
                json.addProperty("success", connected && systemAuthenticated);

                JsonObject response = new JsonObject();
                response.addProperty("connected", connected);
                response.addProperty("authenticated", systemAuthenticated);

                json.add("errorMessage", response);

                return Response.ok(gson.toJson(json), MediaType.APPLICATION_JSON).build();
            }

            String userPrincipalName = null;
            boolean loginAuthenticated = false;

            JsonObject json = new JsonObject();

            try
            {
                final LdapEntry entry = ldapConnector.search(connection,
                                                             ldapConfig.getSearchBase(),
                                                             ldapConfig.getSearchPattern(),
                                                             "*",
                                                             ldapConfig.getPrincipal(),
                                                             ldapConfig.isActiveDirectory(),
                                                             ldapConfig.getGroupSearchBase(),
                                                             ldapConfig.getGroupIdAttribute(),
                                                             ldapConfig.getGroupSearchPattern());

                if (entry != null)
                {
                    userPrincipalName = entry.getBindPrincipal();

                    json.addProperty("entry", entry.toString());
                    json.addProperty("displayName",
                                     entry.getAttributes().getOrDefault(ldapConfig.getDisplayName(),
                                                                        ldapConfig.getPrincipal()));
                }
            }
            catch (CursorException | LdapException e)
            {
                log.error(e.getMessage());
                json.addProperty("errorMessage", e.getMessage());
                return Response.ok(gson.toJson(json), MediaType.APPLICATION_JSON).build();
            }

            try
            {
                loginAuthenticated = ldapConnector.authenticate(connection,
                                                                userPrincipalName,
                                                                ldapConfig.getPassword());
            }
            catch (Exception e)
            {
                log.error(e.getMessage());
                json.addProperty("errorMessage", e.getMessage());
                return Response.ok(gson.toJson(json), MediaType.APPLICATION_JSON).build();
            }

            json.addProperty("success", loginAuthenticated);
            return Response.ok(gson.toJson(json), MediaType.APPLICATION_JSON).build();

        }
        finally
        {
            if (connection != null)
            {
                try
                {
                    connection.close();
                }
                catch (IOException e)
                {
                    log.error("Unable to close LDAP connection.", e);
                }
            }
        }
    }

}

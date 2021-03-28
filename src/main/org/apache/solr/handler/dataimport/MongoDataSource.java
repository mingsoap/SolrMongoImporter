package org.apache.solr.handler.dataimport;


import com.mongodb.*;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.MongoIterable;

import org.bson.BsonDocument;
import org.bson.BsonInt32;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

import static org.apache.solr.handler.dataimport.DataImportHandlerException.SEVERE;
import static org.apache.solr.handler.dataimport.DataImportHandlerException.wrapAndThrow;

/**
 * User: Marco
 * Date: 26/3/21
 * Time: 18:28
 * To change this template use File | Settings | File Templates.
 */


public class MongoDataSource extends DataSource<Iterator<Map<String, Object>>> {

    private static final Logger LOG = LoggerFactory.getLogger(TemplateTransformer.class);

    private MongoCollection<Document> mongoCollection;
    private MongoDatabase mongoDb;
    private MongoClient mongoClient;
    private MongoIterable<Document> mongoIterable;
    private MongoCursor<Document> mongoCursor;

    @Override
    public void init(Context context, Properties initProps) {
        String databaseName = initProps.getProperty(DATABASE);
        String host = initProps.getProperty(HOST, "localhost");
        String port = initProps.getProperty(PORT, "27017");
        String source = initProps.getProperty(SOURCE);
        String mechanism = initProps.getProperty(MECHANISM);
        String username = initProps.getProperty(USERNAME);
        String password = initProps.getProperty(PASSWORD);

        if (databaseName == null) {
            throw new DataImportHandlerException(SEVERE
                    , "Database must be supplied");
        }

        try {

            MongoClientSettings.Builder bld = MongoClientSettings.builder()
                                                .applyToClusterSettings(builder ->
                                                        builder.hosts(Arrays.asList(new ServerAddress(host, Integer.parseInt(port)))))
                                                .readPreference(ReadPreference.secondaryPreferred());
            if (password != null) {
                MongoCredential credential = null;
                switch (mechanism) {
                    case "GSSAPI":
                        credential = MongoCredential.createGSSAPICredential(username);
                        break;
                    case "MONGODB-X509":
                        if (username == null)
                            credential = MongoCredential.createMongoX509Credential();
                        else
                            credential = MongoCredential.createMongoX509Credential(username);
                        break;
                    case "PLAIN":
                        credential = MongoCredential.createPlainCredential(username, source, password.toCharArray());
                        break;
                    case "SCRAM-SHA-1":
                        credential = MongoCredential.createScramSha1Credential(username, source, password.toCharArray());
                        break;
                    case "SCRAM-SHA-256":
                        credential = MongoCredential.createScramSha256Credential(username, source, password.toCharArray());
                        break;
                    default:
                        throw new Exception("unknown mechanism - " + mechanism);
                }
                bld = bld.credential(credential);
            }
            MongoClient mongo = MongoClients.create(bld.build());

            this.mongoClient = mongo;
            this.mongoDb = mongo.getDatabase(databaseName);

            BsonDocument doc = new BsonDocument();
            doc.append("ping", new BsonInt32(1));
            Document response = this.mongoDb.runCommand(doc);
            if (response.getDouble("ok") != 1) {
                throw new DataImportHandlerException(SEVERE
                        , "Mongo Authentication Failed");            }

        } catch (Exception e) {
            throw new DataImportHandlerException(SEVERE
                    , "Unable to connect to Mongo", e);
        }
    }

    @Override
    public Iterator<Map<String, Object>> getData(String query) {
        BsonDocument queryObject = BsonDocument.parse(query);
        LOG.info("Executing MongoQuery: " + query.toString());

        long start = System.currentTimeMillis();
        this.mongoIterable = this.mongoCollection.find((Bson)queryObject);
        LOG.trace("Time taken for mongo :"
                + (System.currentTimeMillis() - start));
        this.mongoCursor = mongoIterable.cursor();
        ResultSetIterator resultSet = new ResultSetIterator(mongoCursor);
        return resultSet.getIterator();
    }

    public Iterator<Map<String, Object>> getData(String query, String collection) {
        this.mongoCollection = this.mongoDb.getCollection(collection);
        return getData(query);
    }

    private class ResultSetIterator {
        MongoCursor<Document> mongoCursor;

        Iterator<Map<String, Object>> rSetIterator;

        public ResultSetIterator(MongoCursor<Document>  mongoCursor) {
            this.mongoCursor = mongoCursor;


            rSetIterator = new Iterator<Map<String, Object>>() {
                public boolean hasNext() {
                    return hasnext();
                }

                public Map<String, Object> next() {
                    return getARow();
                }

                public void remove() {
                }
            };
        }

        public Iterator<Map<String, Object>> getIterator() {
            return rSetIterator;
        }

        private Map<String, Object> getARow() {
            Document mongoObject = getMongoCursor().next();

            Map<String, Object> result = new HashMap<String, Object>();
            Set<String> keys = mongoObject.keySet();
            Iterator<String> iterator = keys.iterator();


            while (iterator.hasNext()) {
                String key = iterator.next();
                Object innerObject = mongoObject.get(key);

                result.put(key, innerObject);
            }

            return result;
        }

        private boolean hasnext() {
            if (mongoCursor == null)
                return false;
            try {
                if (this.mongoCursor.hasNext()) {
                    return true;
                } else {
                    close();
                    return false;
                }
            } catch (MongoException e) {
                e.printStackTrace();
                close();
                wrapAndThrow(SEVERE, e);
                return false;
            }
        }

        private void close() {
            try {
                if (mongoCursor != null)
                    mongoCursor.close();
            } catch (Exception e) {
                LOG.warn("Exception while closing result set", e);
            } finally {
                mongoCursor = null;
            }
        }
    }

    private MongoCursor<Document> getMongoCursor() {
        return this.mongoCursor;
    }

    @Override
    public void close() {

        if (this.mongoCursor != null) {
            this.mongoCursor.close();
        }

        if (this.mongoClient != null) {
            this.mongoClient.close();
        }
    }


    public static final String DATABASE = "database";
    public static final String HOST = "host";
    public static final String PORT = "port";
    public static final String SOURCE = "source";
    public static final String MECHANISM = "mechanism";
    public static final String USERNAME = "username";
    public static final String PASSWORD = "password";

}


package com.stratio.ingestion.sink.mongodb;

import com.mongodb.*;
import org.apache.flume.*;
import org.apache.flume.conf.Configurable;
import org.apache.flume.instrumentation.SinkCounter;
import org.apache.flume.sink.AbstractSink;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public class MongoSink extends AbstractSink implements Configurable {

    private static final Logger log = LoggerFactory.getLogger(MongoSink.class);

    private static final String CONF_URI = "mongoUri";
    private static final String CONF_MAPPING_FILE = "mappingFile";
    private static final String CONF_BATCH_SIZE = "batchSize";
    private static final String CONF_DYNAMIC = "dynamic";
    private static final String CONF_DYNAMIC_DB_FIELD = "dynamicDB";
    private static final String CONF_DYNAMIC_COLLECTION_FIELD = "dynamicCollection";
    private static final int DEFAULT_BATCH_SIZE = 25;
    private static final boolean DEFAULT_DYNAMIC = false;
    private static final String DEFAULT_DYNAMIC_DB_FIELD = "db";
    private static final String DEFAULT_DYNAMIC_COLLECTION_FIELD = "collection";

    private SinkCounter sinkCounter;
    private int batchSize;
    private MongoClient mongoClient;
    private MongoClientURI mongoClientURI;
    private DB mongoDefaultDb;
    private DBCollection mongoDefaultCollection;
    private boolean isDynamicMode;
    private String dynamicDBField;
    private String dynamicCollectionField;
    private EventParser eventParser;

    public MongoSink() {
        super();
    }

    @Override
    public void configure(Context context) {
        try {
            if (!"INJECTED".equals(context.getString(CONF_URI))) {
                this.mongoClientURI = new MongoClientURI(
                        context.getString(CONF_URI),
                        MongoClientOptions.builder().writeConcern(WriteConcern.SAFE)
                );
                this.mongoClient = new MongoClient(mongoClientURI);
                if (mongoClientURI.getDatabase() != null) {
                    this.mongoDefaultDb = mongoClient.getDB(mongoClientURI.getDatabase());
                }
                if (mongoClientURI.getCollection() != null) {
                    this.mongoDefaultCollection = mongoDefaultDb.getCollection(mongoClientURI.getCollection());
                }
            }

            final String mappingFilename = context.getString(CONF_MAPPING_FILE);
            this.eventParser = (mappingFilename == null)?
                    new EventParser()
                    :
                    new EventParser(MappingDefinition.load(mappingFilename));

            this.isDynamicMode = context.getBoolean(CONF_DYNAMIC, DEFAULT_DYNAMIC);
            if (!isDynamicMode && mongoDefaultCollection == null) {
                throw new MongoSinkException("Default MongoDB collection must be specified unless dynamic mode is enabled");
            }
            this.dynamicDBField = context.getString(CONF_DYNAMIC_DB_FIELD, DEFAULT_DYNAMIC_DB_FIELD);
            this.dynamicCollectionField = context.getString(CONF_DYNAMIC_COLLECTION_FIELD, DEFAULT_DYNAMIC_COLLECTION_FIELD);

            this.sinkCounter = new SinkCounter(this.getName());
            this.batchSize = context.getInteger(CONF_BATCH_SIZE, DEFAULT_BATCH_SIZE);
        } catch (IOException ex) {
            throw new MongoSinkException(ex);
        }
    }

    @Override
    public Status process() throws EventDeliveryException {
        Status status = Status.BACKOFF;
        Transaction transaction = this.getChannel().getTransaction();
        try {
            transaction.begin();
            List<Event> eventList = this.takeEventsFromChannel(
                    this.getChannel(), this.batchSize);
            status = Status.READY;
            if (!eventList.isEmpty()) {
                if (eventList.size() == this.batchSize) {
                    this.sinkCounter.incrementBatchCompleteCount();
                } else {
                    this.sinkCounter.incrementBatchUnderflowCount();
                }
                if (!isDynamicMode) {
                    final List<DBObject> documents = this.eventParser.parse(eventList);
                    this.mongoDefaultCollection.insert(documents);
                } else {
                    for (Event event : eventList) {
                        final DBObject document = this.eventParser.parse(event);
                        getDBCollection(event).insert(document);
                    }
                }
                this.sinkCounter.addToEventDrainSuccessCount(eventList.size());
            } else {
                this.sinkCounter.incrementBatchEmptyCount();
            }
            transaction.commit();
            status = Status.READY;
        } catch (ChannelException e) {
            e.printStackTrace();
            transaction.rollback();
            status = Status.BACKOFF;
            this.sinkCounter.incrementConnectionFailedCount();
        } catch (Throwable t) {
            t.printStackTrace();
            transaction.rollback();
            status = Status.BACKOFF;
            if (t instanceof Error) {
                throw new MongoSinkException(t);
            }
        } finally {
            transaction.close();
        }
        return status;
    }

    @Override
    public synchronized void start() {
        this.sinkCounter.start();
        super.start();
    }

    @Override
    public synchronized void stop() {
        this.mongoClient.close();
        this.sinkCounter.stop();
        super.stop();
    }

    private DBCollection getDBCollection(Event event) {
        if (!isDynamicMode) {
            return mongoDefaultCollection;
        }
        final Map<String,String> headers = event.getHeaders();
        final String dbName = headers.get(dynamicDBField);
        final String collectionName = headers.get(dynamicCollectionField);
        if (collectionName == null) {
            if (mongoDefaultCollection == null) {
                throw new MongoSinkException("No collection specified and no default set");
            }
            return mongoDefaultCollection;
        }
        DB db;
        if (dbName == null) {
            if (mongoDefaultDb == null) {
                throw new MongoSinkException("No DB specified and no default set");
            }
            db = mongoDefaultDb;
        } else {
            db = mongoClient.getDB(dbName);
        }
        return db.getCollection(collectionName);
    }

    private List<Event> takeEventsFromChannel(Channel channel, int eventsToTake) {
        List<Event> events = new ArrayList<>();
        for (int i = 0; i < eventsToTake; i++) {
            this.sinkCounter.incrementEventDrainAttemptCount();
            events.add(channel.take());
        }
        events.removeAll(Collections.singleton(null));
        return events;
    }

}

package net.es.lookup.database;

import net.es.lookup.common.Message;
import net.es.lookup.common.ReservedKeys;
import net.es.lookup.common.ReservedValues;
import net.es.lookup.common.Service;
import net.es.lookup.common.exception.internal.DatabaseException;
import net.es.lookup.common.exception.internal.PubSubQueryException;
import net.es.lookup.common.exception.internal.PubSubQueueException;
import net.es.lookup.pubsub.amq.AMQueuePump;
import org.apache.log4j.Logger;
import org.joda.time.DateTime;
import org.joda.time.DateTimeComparator;
import org.joda.time.Instant;
import org.joda.time.format.DateTimeFormatter;
import org.joda.time.format.ISODateTimeFormat;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.quartz.JobDataMap;


import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@DisallowConcurrentExecution
public class MongoDBMaintenanceJob implements Job {

    private static Logger LOG = Logger.getLogger(MongoDBMaintenanceJob.class);
    private ServiceDAOMongoDb db;
    public static String PRUNE_THRESHOLD = "prune_threshold"; //parameter will be set during run time


    public MongoDBMaintenanceJob() {

        this.db = ServiceDAOMongoDb.getInstance();
        LOG.info("Initializing MongoDBPrune...");

    }


    public void execute(JobExecutionContext context) throws JobExecutionException {

        List<Service> result = null;
        LOG.info("Running MongoDBPrune...");
        JobDataMap data = context.getJobDetail().getJobDataMap();
        long prune_threshold = data.getLong(PRUNE_THRESHOLD);
        Instant now = new Instant();
        Instant pTime = now.minus(prune_threshold);
        DateTime pruneTime = pTime.toDateTime();

        try {

            result = db.queryAll();

        } catch (DatabaseException e) {

            LOG.error("DBException! Could not query database");

        }

        List<Message> messages = new ArrayList<Message>();

        if (result != null && result.size() > 0) {

            for (int i = 0; i < result.size(); i++) {

                Map m = result.get(i).getMap();
                DateTimeFormatter fmt = ISODateTimeFormat.dateTime();
                DateTime dt = fmt.parseDateTime((String) m.get(ReservedKeys.RECORD_EXPIRES));
                DateTimeComparator dtc = DateTimeComparator.getInstance();

                if (dtc.compare(dt, pruneTime) < 0) {

                    String uri = (String) m.get(ReservedKeys.RECORD_URI);

                    try {
                        Message tmp = db.deleteService(uri);
                        tmp.add(ReservedKeys.RECORD_STATE, ReservedValues.RECORD_VALUE_STATE_EXPIRE);
                        messages.add(tmp);

                    } catch (Exception e) {

                        LOG.error("Error pruning DB!!");

                    }

                }

            }

        }

        try {
            AMQueuePump amQueuePump = AMQueuePump.getInstance();
            if (amQueuePump.isUp()){
                amQueuePump.fillQueues(messages);
            }
        } catch (PubSubQueueException e) {
            LOG.error("Error sending Expired Record  to Queue");
            LOG.info("Expired Prune: Caught Queue Exception");
        } catch (PubSubQueryException e) {
            LOG.error("Error sending Expired Record  to Queue");
            LOG.info("Expired Prune: Caught Query Exception");
        }

    }


}
package com.devicehive.dao.riak;

import com.basho.riak.client.api.RiakClient;
import com.basho.riak.client.api.cap.UnresolvedConflictException;
import com.basho.riak.client.api.commands.datatypes.CounterUpdate;
import com.basho.riak.client.api.commands.datatypes.UpdateCounter;
import com.basho.riak.client.api.commands.kv.FetchValue;
import com.basho.riak.client.api.commands.mapreduce.BucketMapReduce;
import com.basho.riak.client.core.query.Location;
import com.basho.riak.client.core.query.functions.Function;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;
import java.util.concurrent.ExecutionException;

@Profile({"riak"})
@Repository
public class RiakGenericDao {

    @Autowired
    RiakClient client;

    //TODO increase counter by huger number
    //TODO increment counter to be configurable and have default value
    //TODO here we should use QUORUM or ALL when incrementing the value, that will prevent from problems.
    protected Long getId(Location location) {
        CounterUpdate cu = new CounterUpdate(1);
        UpdateCounter update = new UpdateCounter.Builder(location, cu).withReturnDatatype(true).build();
        UpdateCounter.Response response;
        try {
            response = client.execute(update);
        } catch (ExecutionException | InterruptedException e) {
            throw new RuntimeException(e);
        }
        return response.getDatatype().view();
    }

    protected BucketMapReduce.Builder addPaging(BucketMapReduce.Builder builder, Integer take, Integer skip) {
        if (take != null) {
            int[] args = new int[2];
            args[0] = skip != null ? skip : 0;
            args[1] = args[0] + take;
            return builder.withReducePhase(Function.newNamedJsFunction("Riak.reduceSlice"), args, true);
        } else {
            return builder;
        }
    }

    protected<T> T getOrNull(FetchValue.Response response, Class<T> clazz) throws UnresolvedConflictException {
        if (response.hasValues()) {
            return response.getValue(clazz);
        }
        return null;
    }
}
package com.winterwell.datalog;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.eclipse.jetty.util.ajax.JSON;

import com.winterwell.es.client.ESHttpClient;
import com.winterwell.es.client.SearchRequestBuilder;
import com.winterwell.es.client.agg.Aggregation;
import com.winterwell.es.client.agg.Aggregations;
import com.winterwell.es.client.query.BoolQueryBuilder;
import com.winterwell.es.client.query.ESQueryBuilder;
import com.winterwell.es.client.query.ESQueryBuilders;
import com.winterwell.nlp.query.SearchQuery;
import com.winterwell.utils.ReflectionUtils;
import com.winterwell.utils.StrUtils;
import com.winterwell.utils.containers.ArraySet;
import com.winterwell.utils.containers.Containers;
import com.winterwell.utils.log.Log;
import com.winterwell.utils.time.Dt;
import com.winterwell.utils.time.TUnit;
import com.winterwell.utils.time.Time;
import com.winterwell.web.app.AppUtils;

/**
 * @testedby {@link ESDataLogSearchBuilderTest}
 * @author daniel
 *
 */
public class ESDataLogSearchBuilder {
	
	final Dataspace dataspace;
	int numResults;
	int numExamples; 
	Time start;
	Time end; 
	SearchQuery query;
	List<String> breakdown;
	private boolean doneFlag;
	private ESHttpClient esc;
	
	public ESDataLogSearchBuilder(ESHttpClient esc, Dataspace dataspace) {
		this.dataspace = dataspace;
		this.esc = esc;
	}
	
	public ESDataLogSearchBuilder setBreakdown(List<String> breakdown) {
		assert ! doneFlag;
		this.breakdown = breakdown;
		return this;
	}
	
	public ESDataLogSearchBuilder setQuery(SearchQuery query) {
		assert ! doneFlag;
		this.query = query;
		return this;
	}
	
	public SearchRequestBuilder prepareSearch() {
		doneFlag = true;
		com.winterwell.es.client.query.BoolQueryBuilder filter 
			= AppUtils.makeESFilterFromSearchQuery(query, start, end);
		
		String index = ESStorage.readIndexFromDataspace(dataspace);
		
		SearchRequestBuilder search = esc.prepareSearch(index);
	
		// breakdown(s)
		List<Aggregation> aggs = prepareSearch2_aggregations();
		for (Aggregation aggregation : aggs) {
			search.addAggregation(aggregation);
		}
		
		// Set filter
		search.setQuery(filter);
		
		return search;
	}

	public ESDataLogSearchBuilder setNumResults(int numResults) {
		assert ! doneFlag;
		this.numResults = numResults;
		return this;
	}
	
	
	

	/**
	 * Add aggregations 
	 * @param numResults
	 * @param breakdown
	 * @param filter This may be modified to filter out 0s
	 * @param search
	 */
	List<Aggregation> prepareSearch2_aggregations() 
	{
		List<Aggregation> aggs = new ArrayList();
		for(final String bd : breakdown) {
			if (bd==null) {
				Log.w("DataLog.ES", "null breakdown?! in "+breakdown);
				continue;
			}
			Aggregation agg = prepareSearch3_agg4breakdown(bd);
			aggs.add(agg);
		} // ./breakdown
		
		// add a total count as well for each top-level terms breakdown
		ArrayList noDupes = new ArrayList();
		for(Aggregation agg : aggs.toArray(new Aggregation[0])) {
			String field = agg.getField();
			if (field == null) continue;
			if ( ! "terms".equals(agg.getType())) continue;
			// Avoid dupes. e.g. if both evt/host evt/user were requested, then evt will come up twice
			if (noDupes.contains(field)) continue;
			Aggregation fCountStats = Aggregations.stats(field, ESStorage.count);
			aggs.add(fCountStats);			
			noDupes.add(field);
		}
		
		return aggs;
	}

	/**
	 * 
	 * @param bd Format: bucket-by-fields/ {"report-fields": "operation"} 
	 * 	e.g. "evt" or "evt/time" or "tag/time {"mycount":"avg"}"
	 * NB: the latter part is optional, but if present must be valid json.
	 *   
	 * @return
	 */
	private Aggregation prepareSearch3_agg4breakdown(String bd) {
		// ??Is there a use-case for recursive handling??
		String[] breakdown_output = bd.split("\\{");
		String[] bucketBy = breakdown_output[0].trim().split("/");
		Map<String,String> reportSpec = null;
		if (breakdown_output.length > 1) {
			String json = bd.substring(bd.indexOf("{"), bd.length());
			reportSpec = (Map) JSON.parse(json);
		}
		// loop over the f1/f2 part, building a chain of nested aggregations
		Aggregation root = null;
		Aggregation leaf = null;
		Aggregation previousLeaf = null;
		String s_bucketBy = StrUtils.join(bucketBy, '_');
		for(String field : bucketBy) {
			if (field.equals("time")) {
				leaf = Aggregations.dateHistogram("by_"+s_bucketBy, "time", interval);
			} else {
				leaf = Aggregations.terms("by_"+s_bucketBy, field);
				if (numResults>0) leaf.setSize(numResults);
				// HACK avoid "unset" -> parse exception
				leaf.setMissing(ESQueryBuilders.UNSET);
			}
			if (root==null) {
				root = leaf;
			} else {
				previousLeaf.subAggregation(leaf);
			}
			previousLeaf = leaf;
			// chop down name for the next loop, if there is one.
			if (field.length() < s_bucketBy.length()) {
				s_bucketBy = s_bucketBy.substring(field.length()+1);
			}
		}
		
		// add a count handler?
		if (reportSpec==null) { // no - we're done - return terms
			return root;
		}
		
		// e.g. {"count": "avg"}
		for(String k : reportSpec.keySet()) {
			// Note k should be a numeric field, e.g. count -- not a keyword field!
			Class klass = DataLogEvent.COMMON_PROPS.get(k);
			if ( ! ReflectionUtils.isa(klass, Number.class)) {
				Log.w("ESDataLogSearch", "Possible bug! numeric op on non-numeric field "+k+" in "+bd);
			}
				
			Aggregation myCount = Aggregations.stats(k, k);
			// filter 0s??
			ESQueryBuilder no0 = ESQueryBuilders.rangeQuery(k, 0, null, false);
			Aggregation noZeroMyCount = Aggregations.filtered("no0_"+k, no0, myCount);
			leaf.subAggregation(noZeroMyCount);
		}		
		return root;
	}
	

	public ESDataLogSearchBuilder setStart(Time start) {
		assert ! doneFlag;
		this.start = start;
		return this;
	}

	public ESDataLogSearchBuilder setEnd(Time end) {
		assert ! doneFlag;
		this.end = end;
		return this;
	}

	Dt interval = TUnit.DAY.dt;
	
	public void setInterval(Dt interval) {
		this.interval = interval;
	}
	
}
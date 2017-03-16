package com.winterwell.datalog;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.TreeMap;

import com.winterwell.utils.StrUtils;
import com.winterwell.utils.Utils;
import com.winterwell.utils.containers.ArrayMap;
import com.winterwell.utils.log.KErrorPolicy;
import com.winterwell.utils.time.Time;
import com.winterwell.utils.web.IHasJson;
import com.winterwell.utils.web.SimpleJson;

/**
 * For logging an event -- which can have arbitrary detail.
 * @author daniel
 */
public final class DataLogEvent implements Serializable, IHasJson {
	private static final long serialVersionUID = 1L;

	public static final String EVENTTYPE = "eventType";

	/**
	 * these get special treatment - as direct properties not key/value props.
	 * Which means nicer json + they can have a type in ES.
	 */
	public static final Collection<String> COMMON_PROPS = new HashSet(Arrays.asList(
			// tracking
			"ip","user","url", "domain",
			// common event-defining properties
			"tag", "action", "verb", "as",
			// a few XId properties
			"id", "xid", "oxid", "txid", "uxid", "su",
			// a few scoring/results properties
			"start", "end", "dt", "note", "notes", "score", "x", "y", "z",
			"lat", "lng"
			));
	
	public final double count;
	public final String eventType;
	/**
	 * never null
	 */
	final Map<String, ?> props;

	/**
	 * Does NOT include time-period or dataspace. This is added by the DataLog based on how
	 * it buckets stuff.
	 */
	public final String id;
	
	/**
	 * Does NOT include time-period. This is added by the DataLog based on how
	 * it buckets stuff.
	 */
	public String getId() {
		return id;
	}

	public final String dataspace;

	/**
	 * When should this be set?? This may be the bucket rather than the exact time.
	 */
	public Time time = new Time();
	
	public DataLogEvent(String eventType, Map<String,?> properties) {
		this(DataLog.getDataspace(), 1, eventType, properties);
	}
	
	public DataLogEvent(String dataspace, double count, String eventType, Map<String,?> properties) {
		this.dataspace = StrUtils.normalise(dataspace, KErrorPolicy.ACCEPT).toLowerCase().trim();
		assert ! dataspace.isEmpty() && ! dataspace.contains("/") : dataspace;
//		assert dataspace.equals(StrUtils.toCanonical(dataspace)) : dataspace +" v "+StrUtils.toCanonical(dataspace); 
		this.count = count;
		this.eventType = eventType;
		this.props = properties == null? Collections.EMPTY_MAP : properties;
		this.id = makeId();
		assert ! Utils.isBlank(eventType);
	}

	@Override
	public String toString() {
		return "DataLogEvent[count=" + count + ", eventType=" + eventType + ", props=" + props + ", id=" + id
				+ ", dataspace=" + dataspace + "]";
	}

	/**
	 * Unique based on dataspace, eventType and properties. Does NOT include time though!
	 * @param dataLogEvent
	 */
	private String makeId() {
		if (props==null || props.isEmpty()) {
			return eventType;
		}
		List<String> keys = new ArrayList(props.keySet());
		Collections.sort(keys);
		StringBuilder sb = new StringBuilder();
		for (String key : keys) {
			Object v = props.get(key);
			if (v==null) continue;
			sb.append(key);
			sb.append('=');
			sb.append(v);
			sb.append('&');
		}
		String txt = sb.toString();
		return dataspace+"/"+eventType+"_"+StrUtils.md5(txt);
	}

	@Override
	public String toJSONString() {
		return new SimpleJson().toJson(toJson2());
	}

	/**
	 * Because this has to handle big data, we economise and store either n or v, not both.
	 * {k: string, n: ?number, v: ?string}
	 */
	@Override
	public Map<String,?> toJson2() {
		Map map = new ArrayMap();		
//		map.put("dataspace", dataspace); This is given by the index
		map.put(EVENTTYPE, eventType);
		map.put("time", time.toISOString()); //getTime()); // This is for ES -- which works with epoch millisecs
		map.put("count", count);
		if (props.isEmpty()) return map;
		// privileged props
		for(String p : COMMON_PROPS) {
			Object v = props.get(p);
			if (v!=null) map.put(p, v);
		}
		// others as a list (to avoid hitting the field limit in ES which could happen with dynamic fields)
		List propslist = new ArrayList();
		for(Entry<String, ?> pv : props.entrySet()) {
			if (COMMON_PROPS.contains(pv.getKey())) continue;
			Object v = pv.getValue();
			ArrayMap<String,Object> prop;
			if (v instanceof Number) {
				prop = new ArrayMap(
						"k", pv.getKey(),
						"n", v
						);		
			} else {
				if ( ! Utils.truthy(pv.getValue())) continue;
				prop = new ArrayMap(
						"k", pv.getKey(),
						"v", v.toString()
						);
			}
			propslist.add(prop);
		}
		map.put("props", propslist);
		return map;
	}

	public void setExtraResults(ArrayMap arrayMap) {
		// TODO store extra outputs		
	}
	
}

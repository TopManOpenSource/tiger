/**
 * 
 */
package com.dianping.tiger.engine;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import com.dianping.tiger.engine.event.EventExecutorManager;
import com.dianping.tiger.engine.utils.HostUtil;
import com.dianping.tiger.engine.utils.ScheduleConstants;

/**
 * @author yuantengkai 调度server信息
 */
public class ScheduleServer {

	private static final ScheduleServer instance = new ScheduleServer();

	private ScheduleServer() {
	}

	public static ScheduleServer getInstance() {
		return instance;
	}

	private volatile String zkAddress = "";

	/**
	 * 虚拟节点数
	 */
	private int numOfVisualNode = 100;
	
	/**
	 * 监控url
	 */
	private String monitorIP;

	/**
	 * 该机分配的执行器 k-handlerName v-nodes
	 */
	private ConcurrentHashMap<String, List<Integer>> handlerMap = new ConcurrentHashMap<String, List<Integer>>();

	private CopyOnWriteArrayList<Integer> nodeList = new CopyOnWriteArrayList<Integer>();

	private CopyOnWriteArraySet<String> handlers = new CopyOnWriteArraySet<String>();
	
	/**
	 * 各个执行器的个性化配置
	 * key: handler
	 * value:
	 *   subkey:switchname
	 *   subvalue:boolean
	 */
	private Map<String,ConcurrentMap<String,Boolean>> handlerIndividualConfig = new ConcurrentHashMap<String,ConcurrentMap<String,Boolean>>();
	
	/**
	 * 处理器的线程池大小coresize
	 */
	private int handlerCoreSize = 2;
	
	/**
	 * 处理器的线程池大小maxsize
	 */
	private int handlerMaxSize = 5;
	
	/**
	 * 任务捞取策略
	 */
	private int taskStrategy = ScheduleConstants.TaskFetchStrategy.Multi.getValue();

	/**
	 * handler配置识别码：用于动态感知配置handler是否有变化
	 */
	private AtomicInteger handlerIdentifyCode = new AtomicInteger(0);

	/**
	 * zk中server集群版本,md5(servername0_servername1)
	 */
	private volatile String registerVersion = "0";
	
	/**
	 * zk集群注册时的注册时间
	 */
	private volatile long registerTime = 100;

	/**
	 * 是否可以调度
	 */
	private AtomicBoolean canSchedule = new AtomicBoolean(false);

	/**
	 * 初始化是否完毕
	 */
	private AtomicBoolean initFlag = new AtomicBoolean(false);
	
	/**
	 * 总调度开关
	 */
	private AtomicBoolean scheduleSwitcher = new AtomicBoolean(true);

	/**
	 * 巡航优化开关
	 */
	private AtomicBoolean enableNavigate = new AtomicBoolean(true);

	/**
	 * 反压开关
	 */
	private AtomicBoolean enableBackFetch = new AtomicBoolean(false);
	
	/**
	 * 监控开关
	 */
	private AtomicBoolean enableMonitor = new AtomicBoolean(false);
	
	/**
	 * 是否启用groovy代码
	 */
	private AtomicBoolean enableGroovyCode = new AtomicBoolean(false);

	/**
	 * 当前正在执行的任务数
	 */
	private AtomicInteger runningTaskNum = new AtomicInteger(0);

	private int divideType = ScheduleConstants.NodeDivideMode.DIVIDE_RANGE_MODE.getValue();

	/**
	 * handler组， /handlerGroup即rootPath
	 */
	private String handlerGroup;
	
	/**
	 * zk rootpath eqls:/handlerGroup
	 */
	private String rootPath = "/TIGERZK";

	/**
	 * zk SessionTimeout
	 */
	private int zkSessionTimeout;

	/**
	 * 执行任务+1
	 */
	public void incrRunningTask() {
		runningTaskNum.incrementAndGet();
	}

	/**
	 * 执行完任务-1
	 */
	public void decrRunningTask() {
		int num = runningTaskNum.decrementAndGet();
		if (num < 0) {
			runningTaskNum.set(0);
		}
	}

	/**
	 * 是否有任务在执行
	 * 
	 * @return
	 */
	public boolean isInRunning() {
		return runningTaskNum.get() > 0;
	}

	/**
	 * 获得执行server名称
	 * 
	 * @return
	 */
	public String getServerName() {
		return HostUtil.getHostname();
	}

	/**
	 * 是否可以调度
	 * 
	 * @return
	 */
	public boolean canScheduler() {
		return canSchedule.get() && scheduleSwitcher.get();
	}

	/**
	 * 停止调度
	 */
	public void stopScheduler() {
		this.canSchedule.set(false);
	}

	/**
	 * 开启调度
	 */
	public void startScheduler() {
		this.canSchedule.set(true);
	}

	/**
	 * 复位：停止调度，集群版本置0，且清空正在准备执行的队列,执行版本置0，清空执行器
	 */
	public void reset() {
		this.stopScheduler();
		this.registerVersion = "0";
		this.registerTime = 100;
		EventExecutorManager.getInstance().clearInReadyRunningQueue();
		EventExecutorManager.getInstance().resetExecutorVersion();
		this.clearAllHandler();
	}

	public void clearAllHandler() {
		this.handlerMap.clear();
		this.handlerIdentifyCode.set(0);
	}

	/**
	 * 当前的集群注册版本
	 * 
	 * @return
	 */
	public String getRegisterVersion() {
		return registerVersion;
	}

	/**
	 * 设置当前的集群注册信息
	 * 
	 * @param registerVersion
	 * @param registerTime
	 */
	public void setRegister(String registerVersion, long registerTime) {
		this.registerVersion = registerVersion;
		this.registerTime = registerTime;
	}
	
	/**
	 * 当前注册版本对应的注册时间
	 * @return
	 */
	public long getRegisterTime() {
		return registerTime;
	}

	public void addHandler(String handlerName, List<Integer> nodes) {
		handlerMap.put(handlerName, nodes);
		handlers.add(handlerName);
	}

	public ConcurrentHashMap<String, List<Integer>> getHandlerMap() {
		return handlerMap;
	}
	
	/**
	 * 设置某个handler的配置
	 * @param handler
	 * @param switcherMap
	 */
	public void addHandlerConfig(String handler, HashMap<String,Boolean> switcherMap){
		ConcurrentMap<String, Boolean> cm = new ConcurrentHashMap<String, Boolean>();
		cm.putAll(switcherMap);
		handlerIndividualConfig.put(handler, cm);
	}
	
	/**
	 * 某个handler是否开启巡航模式
	 * @param handler
	 * @return
	 */
	public boolean enableNavigate(String handler){
		if(handlerIndividualConfig.containsKey(handler)){
			ConcurrentMap<String, Boolean> cm = handlerIndividualConfig.get(handler);
			for(Entry<String, Boolean> en : cm.entrySet()){
				String switcher = en.getKey();
				if(ScheduleManagerFactory.ScheduleKeys.enableNavigate.name().equalsIgnoreCase(switcher)){
					return en.getValue();
				}
			}
		}
		return enableNavigate();
	}

	public int getHandlerIdentifyCode() {
		return handlerIdentifyCode.get();
	}

	public void setHandlerIdentifyCode(int handlerIdentifyCode) {
		this.handlerIdentifyCode.set(handlerIdentifyCode);
	}

	public CopyOnWriteArrayList<Integer> getNodeList() {
		return nodeList;
	}

	public void setNodeList(List<Integer> nodeList) {
		this.nodeList.clear();
		this.nodeList.addAll(nodeList);
	}

	public CopyOnWriteArraySet<String> getHandlers() {
		return handlers;
	}

	public int getDivideType() {
		return divideType;
	}

	public void setDivideType(int divideType) {
		this.divideType = divideType;
	}

	public void setScheduleSwitcher(boolean scheduleSwitcher) {
		this.scheduleSwitcher.set(scheduleSwitcher);
	}

	public boolean enableNavigate() {
		return enableNavigate.get();
	}

	public void setEnableNavigate(boolean enableNavigate) {
		this.enableNavigate.set(enableNavigate);
	}

	public boolean enableBackFetch() {
		return enableBackFetch.get();
	}

	public void setEnableBackFetch(boolean enableBackFetch) {
		this.enableBackFetch.set(enableBackFetch);
	}

	public boolean enableMonitor() {
		return enableMonitor.get();
	}

	public void setEnableMonitor(boolean enableMonitor) {
		this.enableMonitor.set(enableMonitor);;
	}
	
	public boolean enableGroovyCode(){
		return enableGroovyCode.get();
	}
	
	public void setEnableGroovyCode(boolean enableGroovyCode){
		this.enableGroovyCode.set(enableGroovyCode);
	}

	public String getZkAddress() {
		return zkAddress;
	}

	public void setZkAddress(String zkAddress) {
		this.zkAddress = zkAddress;
	}

	public String getHandlerGroup() {
		return handlerGroup;
	}

	public void setHandlerGroup(String handlerGroup) {
		this.handlerGroup = handlerGroup;
		this.rootPath = "/" + handlerGroup;
	}

	public String getRootPath() {
		return rootPath;
	}

	/**
	 * Deprecated since 2.0.0<br/>
	 * see setHandlerGroup
	 * @param rootPath
	 */
	@Deprecated
	public void setRootPath(String rootPath) {
		this.rootPath = rootPath;
		this.handlerGroup = rootPath.substring(1);
	}

	public int getZkSessionTimeout() {
		return zkSessionTimeout;
	}

	public void setZkSessionTimeout(int zkSessionTimeout) {
		this.zkSessionTimeout = zkSessionTimeout;
	}

	public int getNumOfVisualNode() {
		return numOfVisualNode;
	}

	public void setNumOfVisualNode(int numOfVisualNode) {
		this.numOfVisualNode = numOfVisualNode;
	}

	public String getMonitorIP() {
		return monitorIP;
	}

	public void setMonitorIP(String monitorIP) {
		this.monitorIP = monitorIP;
	}

	public int getHandlerCoreSize() {
		return handlerCoreSize;
	}

	public void setHandlerCoreSize(int handlerCoreSize) {
		this.handlerCoreSize = handlerCoreSize;
	}

	public int getHandlerMaxSize() {
		return handlerMaxSize;
	}

	public void setHandlerMaxSize(int handlerMaxSize) {
		this.handlerMaxSize = handlerMaxSize;
	}

	public int getTaskStrategy() {
		return taskStrategy;
	}

	public void setTaskStrategy(int taskStrategy) {
		this.taskStrategy = taskStrategy;
	}

	/**
	 * 是否初始化完毕
	 * 
	 * @return
	 */
	public boolean isInitOk() {
		return initFlag.get();
	}

	public void initOk() {
		initFlag.set(true);
	}

}

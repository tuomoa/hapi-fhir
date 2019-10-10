package ca.uhn.fhir.jpa.search;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.interceptor.api.IInterceptorBroadcaster;
import ca.uhn.fhir.jpa.dao.*;
import ca.uhn.fhir.jpa.entity.Search;
import ca.uhn.fhir.jpa.entity.SearchTypeEnum;
import ca.uhn.fhir.jpa.model.search.SearchStatusEnum;
import ca.uhn.fhir.jpa.search.cache.ISearchCacheSvc;
import ca.uhn.fhir.jpa.search.cache.ISearchResultCacheSvc;
import ca.uhn.fhir.jpa.searchparam.SearchParameterMap;
import ca.uhn.fhir.jpa.util.BaseIterator;
import ca.uhn.fhir.model.dstu2.resource.Patient;
import ca.uhn.fhir.rest.api.CacheControlDirective;
import ca.uhn.fhir.rest.api.server.IBundleProvider;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.param.StringParam;
import ca.uhn.fhir.rest.server.exceptions.InternalErrorException;
import ca.uhn.fhir.util.TestUtil;
import org.apache.commons.lang3.Validate;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;
import org.mockito.stubbing.Answer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import javax.persistence.EntityManager;
import java.util.*;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SuppressWarnings({"unchecked"})
@RunWith(MockitoJUnitRunner.class)
public class SearchCoordinatorSvcImplTest {

	private static final Logger ourLog = LoggerFactory.getLogger(SearchCoordinatorSvcImplTest.class);
	private static FhirContext ourCtx = FhirContext.forDstu3();
	@Captor
	ArgumentCaptor<List<Long>> mySearchResultIterCaptor;
	@Mock
	private IFhirResourceDao<?> myCallingDao;
	@Mock
	private EntityManager myEntityManager;
	private int myExpectedNumberOfSearchBuildersCreated = 2;
	@Mock
	private ISearchBuilder mySearchBuilder;
	@Mock
	private ISearchCacheSvc mySearchCacheSvc;
	@Mock
	private ISearchResultCacheSvc mySearchResultCacheSvc;
	private SearchCoordinatorSvcImpl mySvc;
	@Mock
	private PlatformTransactionManager myTxManager;
	private DaoConfig myDaoConfig;
	private Search myCurrentSearch;
	@Mock
	private DaoRegistry myDaoRegistry;
	@Mock
	private IInterceptorBroadcaster myInterceptorBroadcaster;

	@After
	public void after() {
		verify(myCallingDao, atMost(myExpectedNumberOfSearchBuildersCreated)).newSearchBuilder();
	}

	@Before
	public void before() {
		myCurrentSearch = null;

		mySvc = new SearchCoordinatorSvcImpl();
		mySvc.setEntityManagerForUnitTest(myEntityManager);
		mySvc.setTransactionManagerForUnitTest(myTxManager);
		mySvc.setContextForUnitTest(ourCtx);
		mySvc.setSearchCacheServicesForUnitTest(mySearchCacheSvc, mySearchResultCacheSvc);
		mySvc.setDaoRegistryForUnitTest(myDaoRegistry);
		mySvc.setInterceptorBroadcasterForUnitTest(myInterceptorBroadcaster);

		myDaoConfig = new DaoConfig();
		mySvc.setDaoConfigForUnitTest(myDaoConfig);

		when(myCallingDao.newSearchBuilder()).thenReturn(mySearchBuilder);

		when(myTxManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));

		doAnswer(theInvocation -> {
				PersistedJpaBundleProvider provider = (PersistedJpaBundleProvider) theInvocation.getArguments()[0];
				provider.setSearchCoordinatorSvc(mySvc);
				provider.setPlatformTransactionManager(myTxManager);
				provider.setSearchCacheSvc(mySearchCacheSvc);
				provider.setEntityManager(myEntityManager);
				provider.setContext(ourCtx);
				provider.setInterceptorBroadcaster(myInterceptorBroadcaster);
				return null;
		}).when(myCallingDao).injectDependenciesIntoBundleProvider(any(PersistedJpaBundleProvider.class));
	}

	private List<Long> createPidSequence(int from, int to) {
		List<Long> pids = new ArrayList<>();
		for (long i = from; i < to; i++) {
			pids.add(i);
		}
		return pids;
	}

	private Answer<Void> loadPids() {
		return theInvocation -> {
				List<Long> pids = (List<Long>) theInvocation.getArguments()[0];
			List<IBaseResource> resources = (List<IBaseResource>) theInvocation.getArguments()[2];
				for (Long nextPid : pids) {
					Patient pt = new Patient();
					pt.setId(nextPid.toString());
					resources.add(pt);
				}
				return null;
		};
	}

	@Test
	public void testAsyncSearchFailDuringSearchSameCoordinator() {
		SearchParameterMap params = new SearchParameterMap();
		params.add("name", new StringParam("ANAME"));

		List<Long> pids = createPidSequence(10, 800);
		IResultIterator iter = new FailAfterNIterator(new SlowIterator(pids.iterator(), 2), 300);
		when(mySearchBuilder.createQuery(Mockito.same(params), any(), any())).thenReturn(iter);

		IBundleProvider result = mySvc.registerSearch(myCallingDao, params, "Patient", new CacheControlDirective(), null);
		assertNotNull(result.getUuid());
		assertEquals(null, result.size());

		try {
			result.getResources(0, 100000);
		} catch (InternalErrorException e) {
			assertEquals("FAILED", e.getMessage());
		}

	}

	@Test
	public void testAsyncSearchLargeResultSetBigCountSameCoordinator() {
		List<Long> allResults = new ArrayList<>();
		doAnswer(t->{
			List<Long> oldResults = t.getArgument(1, List.class);
			List<Long> newResults = t.getArgument(2, List.class);
			ourLog.info("Saving {} new results - have {} old results", newResults.size(), oldResults.size());
			assertEquals(allResults.size(), oldResults.size());
			allResults.addAll(newResults);
			return null;
		}).when(mySearchResultCacheSvc).storeResults(any(),anyList(),anyList());


		SearchParameterMap params = new SearchParameterMap();
		params.add("name", new StringParam("ANAME"));

		List<Long> pids = createPidSequence(10, 800);
		SlowIterator iter = new SlowIterator(pids.iterator(), 1);
		when(mySearchBuilder.createQuery(any(), any(), any())).thenReturn(iter);
		doAnswer(loadPids()).when(mySearchBuilder).loadResourcesByPid(any(Collection.class), any(Collection.class), any(List.class),  anyBoolean(), any());

		when(mySearchResultCacheSvc.fetchResultPids(any(), anyInt(), anyInt())).thenAnswer(t -> {
			List<Long> returnedValues = iter.getReturnedValues();
			int offset = t.getArgument(1, Integer.class);
			int end = t.getArgument(2, Integer.class);
			end = Math.min(end, returnedValues.size());
			offset = Math.min(offset, returnedValues.size());
			ourLog.info("findWithSearchUuid {} - {} out of {} values", offset, end, returnedValues.size());
			return returnedValues.subList(offset, end);
		});

		when(mySearchResultCacheSvc.fetchAllResultPids(any())).thenReturn(allResults);

		when(mySearchCacheSvc.tryToMarkSearchAsInProgress(any())).thenAnswer(t->{
			Object argument = t.getArgument(0);
			Validate.isTrue( argument instanceof Search, "Argument is " + argument);
			Search search = (Search) argument;
			assertEquals(SearchStatusEnum.PASSCMPLET, search.getStatus());
			search.setStatus(SearchStatusEnum.LOADING);
			return Optional.of(search);
		});

		IBundleProvider result = mySvc.registerSearch(myCallingDao, params, "Patient", new CacheControlDirective(), null);
		assertNotNull(result.getUuid());
		assertEquals(null, result.size());

		List<IBaseResource> resources;

		when(mySearchCacheSvc.save(any())).thenAnswer(t -> {
			Search search = (Search) t.getArguments()[0];
			myCurrentSearch = search;
			return search;
		});
		when(mySearchCacheSvc.fetchByUuid(any())).thenAnswer(t -> {
			return Optional.ofNullable(myCurrentSearch);
		});
		IFhirResourceDao dao = myCallingDao;
		when(myDaoRegistry.getResourceDao(any(String.class))).thenReturn(dao);

		resources = result.getResources(0, 100000);
		assertEquals(790, resources.size());
		assertEquals("10", resources.get(0).getIdElement().getValueAsString());
		assertEquals("799", resources.get(789).getIdElement().getValueAsString());

		ArgumentCaptor<Search> searchCaptor = ArgumentCaptor.forClass(Search.class);
		verify(mySearchCacheSvc, atLeastOnce()).save(searchCaptor.capture());

		assertEquals(790, allResults.size());
		assertEquals(10, allResults.get(0).longValue());
		assertEquals(799, allResults.get(789).longValue());

		myExpectedNumberOfSearchBuildersCreated = 4;
	}

	@Test
	public void testAsyncSearchLargeResultSetSameCoordinator() {
		SearchParameterMap params = new SearchParameterMap();
		params.add("name", new StringParam("ANAME"));

		List<Long> pids = createPidSequence(10, 800);
		SlowIterator iter = new SlowIterator(pids.iterator(), 2);
		when(mySearchBuilder.createQuery(same(params), any(), any())).thenReturn(iter);

		doAnswer(loadPids()).when(mySearchBuilder).loadResourcesByPid(any(Collection.class), any(Collection.class), any(List.class),  anyBoolean(), any());

		IBundleProvider result = mySvc.registerSearch(myCallingDao, params, "Patient", new CacheControlDirective(), null);
		assertNotNull(result.getUuid());
		assertEquals(null, result.size());

		List<IBaseResource> resources;

		resources = result.getResources(0, 30);
		assertEquals(30, resources.size());
		assertEquals("10", resources.get(0).getIdElement().getValueAsString());
		assertEquals("39", resources.get(29).getIdElement().getValueAsString());

	}

	/**
	 * Subsequent requests for the same search (i.e. a request for the next
	 * page) within the same JVM will not use the original bundle provider
	 */
	@Test
	public void testAsyncSearchLargeResultSetSecondRequestSameCoordinator() {
		SearchParameterMap params = new SearchParameterMap();
		params.add("name", new StringParam("ANAME"));

		List<Long> pids = createPidSequence(10, 800);
		IResultIterator iter = new SlowIterator(pids.iterator(), 2);
		when(mySearchBuilder.createQuery(same(params), any(), any())).thenReturn(iter);
		when(mySearchCacheSvc.save(any())).thenAnswer(t -> t.getArguments()[0]);
		doAnswer(loadPids()).when(mySearchBuilder).loadResourcesByPid(any(Collection.class), any(Collection.class), any(List.class),  anyBoolean(), any());

		IBundleProvider result = mySvc.registerSearch(myCallingDao, params, "Patient", new CacheControlDirective(), null);
		assertNotNull(result.getUuid());
		assertEquals(null, result.size());

		ArgumentCaptor<Search> searchCaptor = ArgumentCaptor.forClass(Search.class);
		verify(mySearchCacheSvc, atLeast(1)).save(searchCaptor.capture());
		Search search = searchCaptor.getValue();
		assertEquals(SearchTypeEnum.SEARCH, search.getSearchType());

		List<IBaseResource> resources;
		PersistedJpaBundleProvider provider;

		resources = result.getResources(0, 10);
		assertNull(result.size());
		assertEquals(10, resources.size());
		assertEquals("10", resources.get(0).getIdElement().getValueAsString());
		assertEquals("19", resources.get(9).getIdElement().getValueAsString());

		when(mySearchCacheSvc.fetchByUuid(eq(result.getUuid()))).thenReturn(Optional.of(search));

		/*
		 * Now call from a new bundle provider. This simulates a separate HTTP
		 * client request coming in.
		 */
		provider = new PersistedJpaBundleProvider(null, result.getUuid(), myCallingDao);
		resources = provider.getResources(10, 20);
		assertEquals(10, resources.size());
		assertEquals("20", resources.get(0).getIdElement().getValueAsString());
		assertEquals("29", resources.get(9).getIdElement().getValueAsString());

		myExpectedNumberOfSearchBuildersCreated = 4;
	}


	@Test
	public void testAsyncSearchSmallResultSetSameCoordinator() {
		SearchParameterMap params = new SearchParameterMap();
		params.add("name", new StringParam("ANAME"));

		List<Long> pids = createPidSequence(10, 100);
		SlowIterator iter = new SlowIterator(pids.iterator(), 2);
		when(mySearchBuilder.createQuery(same(params), any(), any())).thenReturn(iter);

		doAnswer(loadPids()).when(mySearchBuilder).loadResourcesByPid(any(Collection.class), any(Collection.class), any(List.class),  anyBoolean(), any());

		IBundleProvider result = mySvc.registerSearch(myCallingDao, params, "Patient", new CacheControlDirective(), null);
		assertNotNull(result.getUuid());
		assertEquals(90, result.size().intValue());

		List<IBaseResource> resources = result.getResources(0, 30);
		assertEquals(30, resources.size());
		assertEquals("10", resources.get(0).getIdElement().getValueAsString());
		assertEquals("39", resources.get(29).getIdElement().getValueAsString());

	}

	@Test
	public void testGetPage() {
		Pageable page = SearchCoordinatorSvcImpl.toPage(50, 73);
		assertEquals(50, page.getOffset());
		assertEquals(23, page.getPageSize());
	}

	@Test
	public void testLoadSearchResultsFromDifferentCoordinator() {
		final String uuid = UUID.randomUUID().toString();

		final Search search = new Search();
		search.setUuid(uuid);
		search.setSearchType(SearchTypeEnum.SEARCH);
		search.setResourceType("Patient");

		when(mySearchCacheSvc.fetchByUuid(eq(uuid))).thenReturn(Optional.of(search));
		doAnswer(loadPids()).when(mySearchBuilder).loadResourcesByPid(any(Collection.class), any(Collection.class), any(List.class),  anyBoolean(), any());

		PersistedJpaBundleProvider provider;
		List<IBaseResource> resources;

		new Thread(() -> {
				try {
					Thread.sleep(1000);
				} catch (InterruptedException e) {
					// ignore
				}

			when(mySearchResultCacheSvc.fetchResultPids(any(Search.class), anyInt(), anyInt())).thenAnswer(theInvocation -> {
				ArrayList<Long> results = new ArrayList<>();
				for (long i = theInvocation.getArgument(1, Integer.class); i < theInvocation.getArgument(2, Integer.class); i++) {
					results.add(i + 10L);
				}

				return results;
				});
				search.setStatus(SearchStatusEnum.FINISHED);
		}).start();

		/*
		 * Now call from a new bundle provider. This simulates a separate HTTP
		 * client request coming in.
		 */
		provider = new PersistedJpaBundleProvider(null, uuid, myCallingDao);
		resources = provider.getResources(10, 20);
		assertEquals(10, resources.size());
		assertEquals("20", resources.get(0).getIdElement().getValueAsString());
		assertEquals("29", resources.get(9).getIdElement().getValueAsString());

		provider = new PersistedJpaBundleProvider(null, uuid, myCallingDao);
		resources = provider.getResources(20, 40);
		assertEquals(20, resources.size());
		assertEquals("30", resources.get(0).getIdElement().getValueAsString());
		assertEquals("49", resources.get(19).getIdElement().getValueAsString());

		myExpectedNumberOfSearchBuildersCreated = 3;
	}

	@Test
	public void testSynchronousSearch() {
		SearchParameterMap params = new SearchParameterMap();
		params.setLoadSynchronous(true);
		params.add("name", new StringParam("ANAME"));

		List<Long> pids = createPidSequence(10, 800);
		when(mySearchBuilder.createQuery(same(params), any(), any())).thenReturn(new ResultIterator(pids.iterator()));

		doAnswer(loadPids()).when(mySearchBuilder).loadResourcesByPid(any(Collection.class), any(Collection.class), any(List.class),  anyBoolean(), any());

		IBundleProvider result = mySvc.registerSearch(myCallingDao, params, "Patient", new CacheControlDirective(), null);
		assertNull(result.getUuid());
		assertEquals(790, result.size().intValue());

		List<IBaseResource> resources = result.getResources(0, 10000);
		assertEquals(790, resources.size());
		assertEquals("10", resources.get(0).getIdElement().getValueAsString());
		assertEquals("799", resources.get(789).getIdElement().getValueAsString());
	}

	@Test
	public void testSynchronousSearchUpTo() {
		SearchParameterMap params = new SearchParameterMap();
		params.setLoadSynchronousUpTo(100);
		params.add("name", new StringParam("ANAME"));

		List<Long> pids = createPidSequence(10, 800);
		when(mySearchBuilder.createQuery(Mockito.same(params), any(), nullable(RequestDetails.class))).thenReturn(new ResultIterator(pids.iterator()));

		pids = createPidSequence(10, 110);
		doAnswer(loadPids()).when(mySearchBuilder).loadResourcesByPid(eq(pids), any(Collection.class), any(List.class), anyBoolean(), nullable(RequestDetails.class));

		IBundleProvider result = mySvc.registerSearch(myCallingDao, params, "Patient", new CacheControlDirective(), null);
		assertNull(result.getUuid());
		assertEquals(100, result.size().intValue());

		List<IBaseResource> resources = result.getResources(0, 10000);
		assertEquals(100, resources.size());
		assertEquals("10", resources.get(0).getIdElement().getValueAsString());
		assertEquals("109", resources.get(99).getIdElement().getValueAsString());
	}

	public static class FailAfterNIterator extends BaseIterator<Long> implements IResultIterator {

		private int myCount;
		private IResultIterator myWrap;

		FailAfterNIterator(IResultIterator theWrap, int theCount) {
			myWrap = theWrap;
			myCount = theCount;
		}

		@Override
		public boolean hasNext() {
			return myWrap.hasNext();
		}

		@Override
		public Long next() {
			myCount--;
			if (myCount == 0) {
				throw new NullPointerException("FAILED");
			}
			return myWrap.next();
		}

		@Override
		public int getSkippedCount() {
			return myWrap.getSkippedCount();
		}

		@Override
		public void close() {
			// nothing
		}
	}

	public static class ResultIterator extends BaseIterator<Long> implements IResultIterator {

		private final Iterator<Long> myWrap;

		ResultIterator(Iterator<Long> theWrap) {
			myWrap = theWrap;
		}

		@Override
		public boolean hasNext() {
			return myWrap.hasNext();
		}

		@Override
		public Long next() {
			return myWrap.next();
		}

		@Override
		public int getSkippedCount() {
			return 0;
		}

		@Override
		public void close() {
			// nothing
		}
	}

	/**
	 * THIS CLASS IS FOR UNIT TESTS ONLY - It is delioberately inefficient
	 * and keeps things in memory.
	 * <p>
	 * Don't use it in real code!
	 */
	public static class SlowIterator extends BaseIterator<Long> implements IResultIterator {

		private static final Logger ourLog = LoggerFactory.getLogger(SlowIterator.class);
		private final IResultIterator myResultIteratorWrap;
		private int myDelay;
		private Iterator<Long> myWrap;
		private List<Long> myReturnedValues = new ArrayList<>();

		SlowIterator(Iterator<Long> theWrap, int theDelay) {
			myWrap = theWrap;
			myDelay = theDelay;
			myResultIteratorWrap = null;
		}

		List<Long> getReturnedValues() {
			return myReturnedValues;
		}

		@Override
		public boolean hasNext() {
			boolean retVal = myWrap.hasNext();
			if (!retVal) {
				ourLog.info("No more results remaining");
			}
			return retVal;
		}

		@Override
		public Long next() {
			try {
				Thread.sleep(myDelay);
			} catch (InterruptedException e) {
				// ignore
			}
			Long retVal = myWrap.next();
			myReturnedValues.add(retVal);
			return retVal;
		}

		@Override
		public int getSkippedCount() {
			if (myResultIteratorWrap == null) {
				return 0;
			} else {
				return myResultIteratorWrap.getSkippedCount();
			}
		}

		@Override
		public void close() {
			// nothing
		}
	}

	@AfterClass
	public static void afterClassClearContext() {
		TestUtil.clearAllStaticFieldsForUnitTest();
	}

}

package de.jexcellence.hibernate.repository;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import de.jexcellence.hibernate.repository.AbstractCRUDRepository;
import jakarta.persistence.EntityManagerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * A generic repository that provides caching and asynchronous operations for entities.
 * <p>
 * This repository extends AbstractCRUDRepository and wraps common caching behavior, so that
 * repositories for different entities (e.g. User, Generator) don’t have to duplicate the cache logic.
 * </p>
 *
 * @param <T>  the entity type
 * @param <ID> the type of the entity ID
 * @param <K>  the type of the key used for caching (extracted from the entity)
 */
public class GenericCachedRepository<T, ID, K> extends AbstractCRUDRepository<T, ID> {

	private final ExecutorService executor;
	private final Cache<K, T> cache;
	private final Function<T, K> keyExtractor;
	private final String cacheAttributeName;

	/**
	 * Constructs a new GenericCachedRepository.
	 *
	 * @param executor            the executor service for asynchronous operations
	 * @param entityManagerFactory the entity manager factory
	 * @param entityClass         the class type of the entity
	 * @param keyExtractor        function to extract the cache key from an entity
	 * @param cacheAttributeName  the attribute name used for querying the unique key (e.g., "uniqueId")
	 */
	public GenericCachedRepository(
		ExecutorService executor,
		EntityManagerFactory entityManagerFactory,
		Class<T> entityClass,
		Function<T, K> keyExtractor,
		String cacheAttributeName
	) {
		super(entityManagerFactory, entityClass);
		this.executor = executor;
		this.cache = Caffeine.newBuilder().expireAfterWrite(30, TimeUnit.MINUTES).build();
		this.keyExtractor = keyExtractor;
		this.cacheAttributeName = cacheAttributeName;
	}

	@Override
	public List<T> findAll(int pageNumber, int pageSize) {
		Map<K, T> map = cache.asMap();
		if (!map.isEmpty()) {
			return List.copyOf(map.values());
		}
		List<T> list = super.findAll(pageNumber, pageSize);
		list.forEach(entity -> cache.put(keyExtractor.apply(entity), entity));
		return list;
	}

	@Override
	public CompletableFuture<List<T>> findAllAsync(int pageNumber, int pageSize) {
		return CompletableFuture.supplyAsync(() -> this.findAll(pageNumber, pageSize), executor);
	}

	/**
	 * Finds an entity by its cache key.
	 *
	 * @param key the cache key value
	 * @return the entity found, or null if not present
	 */
	public T findByCacheKey(final K key) {
		T cached = cache.getIfPresent(key);
		if (cached != null) return cached;
		T found = super.findByAttributes(Map.of(cacheAttributeName, key));
		if (found != null) {
			cache.put(key, found);
		}
		return found;
	}

	/**
	 * Asynchronously finds an entity by its cache key.
	 *
	 * @param key the cache key value
	 * @return a CompletableFuture with the found entity
	 */
	public CompletableFuture<T> findByCacheKeyAsync(final K key) {
		return CompletableFuture.supplyAsync(() -> this.findByCacheKey(key), executor);
	}

	@Override
	public T create(T entity) {
		T created = super.create(entity);
		cache.put(keyExtractor.apply(created), created);
		return created;
	}

	@Override
	public T update(T entity) {
		T updated = super.update(entity);
		cache.put(keyExtractor.apply(updated), updated);
		return updated;
	}

	@Override
	public void delete(ID id) {
		super.delete(id);
		// Remove from cache any entity whose getId() matches the given id.
		cache.asMap().values().removeIf(entity -> {
			Object entityId = getIdFromEntity(entity);
			return entityId != null && entityId.equals(id);
		});
	}

	/**
	 * Attempts to extract the ID from an entity using reflection.
	 * Assumes the entity has a public getId() method.
	 *
	 * @param entity the entity from which to extract the ID
	 * @return the ID, or null if extraction fails
	 */
	private Object getIdFromEntity(T entity) {
		try {
			return entity.getClass().getMethod("getId").invoke(entity);
		} catch (Exception e) {
			return null;
		}
	}
}
---
name: kotlin-udf-architecture
description: Enforce Unidirectional Data Flow (UDF), ViewModel best practices, StateFlow patterns, Coroutine dispatchers, Room DB design, and Hilt DI guardrails in Kotlin/Android projects. Trigger when building or refactoring ViewModels, Repositories, UseCases, DAOs, or dependency injection modules.
---

# Kotlin UDF & Modern Android Architecture

## 1. Unidirectional Data Flow (UDF) & State Contract
Every screen must follow a strict unidirectional data flow loop: `UI -> Action -> ViewModel -> StateFlow -> UI`.

### State & Action Contract Rules
- Group all screen state into a single immutable data class named `<Screen>UiState` with default values for every property.
- Define user interactions in a `sealed interface <Screen>UiAction` (e.g., `data class UpdateQuery(val query: String) : SearchUiAction`, `data object RetryClicked : SearchUiAction`).
- Mark complex external models inside `UiState` as `@Immutable` or use Kotlinx Immutable Collections (`ImmutableList<T>`) to prevent unnecessary Compose recompositions.

## 2. ViewModel & StateFlow Guardrails
- **Single Source of Truth:** ViewModels expose state exclusively as `StateFlow<UiState>` using `.stateIn(scope = viewModelScope, started = SharingStarted.WhileSubscribed(5_000), initialValue = <Screen>UiState())`.
- **Never expose mutable state:** Keep `MutableStateFlow` private and update it atomically via `_uiState.update { it.copy(...) }`. Never use `LiveData`.
- **Single Action Entrypoint:** Expose `fun onAction(action: <Screen>UiAction)` in the ViewModel rather than scattered public methods.

## 3. Coroutine Dispatchers & Threading
- **Never Hardcode Dispatchers:** Do not reference `Dispatchers.IO` or `Dispatchers.Default` directly inside ViewModels or Repositories.
- **Inject Dispatchers via Hilt:** Always inject `@IoDispatcher private val ioDispatcher: CoroutineDispatcher` so unit tests can inject `UnconfinedTestDispatcher` or `StandardTestDispatcher`.

## 4. Room Database & Repository Best Practices
- **One-Shot Queries:** All non-reactive DAO read/write methods (`@Upsert`, `@Delete`, one-shot `@Query`) must be `suspend` functions.
- **Reactive Streams:** Expose observable queries from DAOs as `Flow<List<Entity>>` and map them to external domain models inside the Repository layer (`dao.observeItems().map { entities -> entities.map(Entity::toDomain) }`).
- **Layer Boundaries:** Never expose Room `@Entity` classes or Retrofit DTOs directly to `UiState` or Compose screens; map to clean domain/UI models in the Repository.

## 5. Dependency Injection (Hilt) Rules
- Prefer `@Inject constructor(...)` on classes and `@Binds` inside abstract `@Module` classes for interface-to-implementation bindings over `@Provides`.
- Keep `@Singleton` scoped strictly to stateless or shared infrastructure (Room Database, OkHttpClient, DataStore, CoroutineDispatchers).
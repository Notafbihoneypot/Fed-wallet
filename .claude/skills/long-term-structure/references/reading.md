# Reading

Grouped by the problem they help with. Start with the first entry in each group.

## Strategic design and managing complexity

- John Ousterhout, *A Philosophy of Software Design*, 2nd edition. The source of tactical vs strategic programming, deep modules, information hiding and "define errors out of existence". Short; read it first.
- David Parnas, "On the Criteria To Be Used in Decomposing Systems into Modules", *Communications of the ACM*, 1972. The original argument for splitting by hidden knowledge rather than by processing steps. Eight pages, still the clearest statement.
- Kent Beck, *Tidy First?*, O'Reilly, 2023. Small structural changes, done separately from behaviour changes, sized to enable the next change.
- Titus Winters, Tom Manshreck and Hyrum Wright (eds.), *Software Engineering at Google*, O'Reilly, 2020. "Software engineering is programming integrated over time"; Hyrum's law; dependency management. Long; read the chapters on time, change and dependencies.

## Domain-driven design

- Vaughn Vernon, *Domain-Driven Design Distilled*, Addison-Wesley, 2016. The short practical introduction: bounded contexts, context maps, ubiquitous language.
- Scott Wlaschin, *Domain Modeling Made Functional*, Pragmatic Bookshelf, 2018. Domain modelling with algebraic types and making illegal states unrepresentable. Written in F#, but it maps almost directly onto Rust enums and newtypes; the most useful DDD book for this codebase.
- Dan Bergh Johnsson, Daniel Deogun and Daniel Sawano, *Secure by Design*, Manning, 2019. DDD applied to security: domain primitives, read-once objects, failing fast at boundaries. The closest match to "DDD with security first".
- Eric Evans, *Domain-Driven Design*, Addison-Wesley, 2003. The original; dense. Read Part IV (strategic design) after the shorter books.

## Rust-specific

- Alexis King, "Parse, don't validate" (blog post, 2019). Why validated types beat repeated checks.
- The Rust API Guidelines (rust-lang.github.io/api-guidelines). Concrete conventions for naming, type safety, and future-proof public APIs; useful for the FFI crate.
- Jon Gjengset, *Rust for Rustaceans*, No Starch Press, 2021. Type design, API design and testing at an intermediate-to-advanced level.

## Changing existing code safely

- Michael Feathers, *Working Effectively with Legacy Code*, Prentice Hall, 2004. Characterization tests and seams; how to split a large class such as the view model without breaking it.
- Martin Fowler, *Refactoring*, 2nd edition, Addison-Wesley, 2018. The catalogue of behaviour-preserving moves.

## Finding where to invest

- Adam Tornhill, *Your Code as a Crime Scene*, 2nd edition, Pragmatic Bookshelf, 2024. Hotspot analysis from version-control history: complexity × change frequency, change coupling, knowledge distribution. The structure report's hotspot section is a minimal version of this.

## Decisions and architecture records

- Michael Nygard, "Documenting Architecture Decisions" (blog post, 2011). The short original proposal for ADRs.

## Android structure

- Android Developers, "Guide to app architecture" (developer.android.com/topic/architecture). Unidirectional data flow, state holders and splitting view models; directly relevant to the view-model hotspot.

## Security

- Ross Anderson, *Security Engineering*, 3rd edition, Wiley, 2020. Threat models, protocols and the economics of security; good for reasoning about the node and the device as adversaries.

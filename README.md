
# Common Transit Convention Guarantee Balance: Router
This is a microservice that supports the HMRC Guarantee Balance service, selecting and routing guarantee balance requests to
the correct destination, and validating that the supplied GRN and Access Code match expected values.

**This is not the public API** -- you may wish to visit our documentation on [version 2 of the CTC Guarantee Balance API](https://developer.service.hmrc.gov.uk/api-documentation/docs/api/service/common-transit-convention-guarantee-balance/2.0)
instead. 

## Prerequisites

- Scala 2.13.12
- Java 21
- sbt 1.9.7
- [Service Manager](https://github.com/hmrc/service-manager)

## Development Setup

Run from the console using: `sbt run`

### Highlighted SBT Tasks
| Task                    | Description                                                                                          | Command                             |
|:------------------------|:-----------------------------------------------------------------------------------------------------|:------------------------------------|
| run                     | Runs the application with the default configured port                                                | ```$ sbt run```                     |
| test                    | Runs the standard unit tests                                                                         | ```$ sbt test```                    |
| it/test                 | Runs the integration tests                                                                           | ```$ sbt it/test ```                |
| dependencyCheck         | Runs dependency-check against the current project. It aggregates dependencies and generates a report | ```$ sbt dependencyCheck```         |
| dependencyUpdates       | Shows a list of project dependencies that can be updated                                             | ```$ sbt dependencyUpdates```       |
| dependencyUpdatesReport | Writes a list of project dependencies to a file                                                      | ```$ sbt dependencyUpdatesReport``` |

## Related API documentation

- [Version 2 of the CTC Guarantee Balance API](https://developer.service.hmrc.gov.uk/api-documentation/docs/api/service/common-transit-convention-guarantee-balance/2.0)

## Helpful information

Guides for the related public Common Transit Convention Guarantee Balance API are on the [HMRC Developer Hub](https://developer.service.hmrc.gov.uk/api-documentation/docs/using-the-hub)

## Reporting Issues

If you have any issues relating to the Common Transit Convention Guarantee Balance API, please raise them through our [public API](https://github.com/hmrc/common-transit-convention-guarantee-balance#reporting-issues).

## License

This code is open source software licensed under the [Apache 2.0 License]("http://www.apache.org/licenses/LICENSE-2.0.html").

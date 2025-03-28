# toolarium-icap-client

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [ 1.3.10 ] - 2025-03-28

## [ 1.3.9 ] - 2025-03-28
### Fixed
- Bugfix issue resource with no name (null or empty).

## [ 1.3.8 ] - 2025-02-17
### Added
- Added getHeaders on ICAPRemoteServiceConfiguration (issue #19).

### Changed
- Refactoring of maxRequestTimeout to differentiate between connection and read timeout.

## [ 1.3.7 ] - 2025-01-26
### Changed
- Refactoring method setSocketTimeout into setDefaultSocketTimeout on the ICAPConnectionManager to set a default max connection timeout.
- Added max request timeout on ICAPRequestInformation (issue #18).
- Update method for custom headers.

### Fixed
- Bugfix issue #17 to propagate IOExectption in case a connection is failed.

## [ 1.3.6 ] - 2025-01-25
### Added
- Added support of custom headers. The reserved headers such as Host, Connection, User-Agent, Preview, Encapsulated, Allow can not be overwritten.

### Fixed
- Bugfix issue #15 to handle socket timeout properly.

## [ 1.3.5 ] - 2024-12-11
### Added
- Added method setSocketTimeout on ICAPConnectionManager (see #11).

### Fixed
- Issue "wrong host" (see #13).

## [ 1.3.4 ] - 2024-11-04
### Changed
- Update libraries.

### Fixed
- Creation of icaps connection by ICAPClientFactory. 

## [ 1.3.3 ] - 2024-04-14
### Changed
- Update libraries.

## [ 1.3.2 ] - 2023-08-28
### Fixed
- Proper handling an empty file.

## [ 1.3.1 ] - 2023-08-01
### Added
- Broader support of different Virus scanners.
- McAffee test and support by @techorix.

## [ 1.3.0 ] - 2023-05-11
### Added
- Added switch to enable or disable compare input/output content and set header field. 
  In many cases the server don't let you compare it (new by default=false).

### Fixed
- Issues resource handling.

## [ 1.2.0 ] - 2022-11-12
### Added
- FILEMOD support.
- REQMOD proper support (Bugfix #6).

### Fixed
- Bugfix #5: Bugfix non default port issue.
- Bugfix #7: Typo in log.

## [ 1.1.0 ] - 2022-04-07
### Added
- Support for secure connection, e.g. icaps://<hostname>:<port>/srv_clamav.

## [ 1.0.2 ] - 2022-04-04
### Fixed
- Send properly remaining file size.

## [ 1.0.1 ] - 2021-10-22
### Added
- Support for connecting by a url string ICAPClientFactory, e.g. icap://<hostname>:<port>/srv_clamav.

## [ 1.0.0 ] - 2021-10-21
### Changed
- Setup initial version.

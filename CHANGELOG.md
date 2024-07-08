# Change Log

All notable changes to this project will be documented in this file.
This change log follows the conventions of
[keepachangelog.com](http://keepachangelog.com/).

## [Unreleased][unreleased]

### Changed

- Allow negative latencies thanks to user reports of beat packets
  arriving before beats are heard.
- Updated to incorporate much newer versions of underlying libraries,
  including Beat Link, which adds support for six players when used
  with a DJM-V10, and high-precision tracking of playback position for
  the CDJ-3000, including movements and looping inside of individual
  beats. Also adds tentative support for working with Opus Quad
  hardware with the help of metadata exports. Updated lib-carabiner
  incorporates Ableton Link version 3.1.2.

## [0.1.1] - 2020-12-28

### Changed

- Updated `beat-Link` and `beat-carabiner` libraries to incorporate
  fixes and new features as well as Ableton Link 3.0.3.

- Added type hints for the Clojure compiler to avoid reflection and
  improve runtime performance.

## [0.1.0] - 2020-09-12

First set of features to receive feedback that people are successfully
using.

[unreleased]: https://github.com/Deep-Symmetry/open-beat-control/compare/v0.1.1...HEAD
[0.1.1]: https://github.com/Deep-Symmetry/open-beat-control/compare/v0.1.0...v0.1.1
[0.1.0]: https://github.com/Deep-Symmetry/open-beat-control/compare/4b8707c725ee7395c6844a8eb56c91900387408a...v0.1.0

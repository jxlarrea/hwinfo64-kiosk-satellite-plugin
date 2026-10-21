# Changelog

## Unreleased

### Added

- Read the sensors of one or more PCs through HWiNFO's Remote Sensor Monitoring on TCP port 27007, with a Test button that reports what each PC answers.
- Send picked readings to Home Assistant as sensors with units and device classes. Yes or no readings become binary sensors and every PC gets an online sensor.
- Pick readings from one searchable list of every PC's readings, grouped by computer and device and showing current values.
- Add the PC sensors screensaver, drawn natively, with panels, stacked columns, footer logos, value statistics, red limits, colors and text size. Build makes a starter layout from a PC's readings.
- Switch to PC sensors automatically while a reading stays past a limit, such as a game's frame rate, and hand the screensaver back afterward. The Show PC sensors action starts it on demand.
- Add the Installation section with the Install in Kiosk Satellite button and the manual steps.
- Use the official HWiNFO logo as the plugin icon.
- Rename the plugin to HWiNFO Monitor.

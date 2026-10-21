# Changelog

## Unreleased

### Added

- Read the sensors of one or more PCs through HWiNFO's Remote Sensor Monitoring on TCP port 27007, with a Test button that reports what each PC answers.
- Send picked sensors to Home Assistant with units and device classes. Yes or no sensors become binary sensors and every PC gets an online sensor.
- Pick sensors from one searchable list of every PC's sensors, grouped by computer and device and showing current values.
- Add the PC sensors screensaver, drawn natively, with panels, stacked columns, footer logos, value statistics, red limits, colors and text size. Build makes a starter layout from a PC's sensors.
- Switch to PC sensors automatically while a sensor stays past a limit, such as a game's frame rate, and hand the screensaver back afterward. The Show PC sensors action starts it on demand.
- Add the Installation section with the Install in Kiosk Satellite button and the manual steps.
- Use the official HWiNFO logo as the plugin icon.
- Rename the plugin to HWiNFO Monitor.
- Name HWiNFO's sensors the way HWiNFO does and type the automatic screensaver and red tile limits as numbers.
- Show the values sent to Home Assistant inside the Home Assistant group.
- Draw the screensaver in the Kiosk Satellite look with its typeface, radii and spacing. It follows the Kiosk Satellite theme in light and dark until Custom colors is on, which adds a text color.
- Add Value size for the sensor values. Text size now also scales the sensor labels.

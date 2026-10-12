"""Execute the production shell reader against isolated power_supply fixtures."""
import json, pathlib, subprocess, tempfile
source = pathlib.Path('app/src/main/java/dev/rikumi/flymemod/ChargingInputReader.java').read_text()
command = json.loads(source.split('static final String COMMAND = ', 1)[1].split(';\n', 1)[0])
checks = 0
with tempfile.TemporaryDirectory() as tmp:
    root = pathlib.Path(tmp)
    command = command.replace('/sys/class/power_supply', tmp)
    def supply(name, kind, online='1', voltage='8226000', current='1891000'):
        p = root / name
        p.mkdir(exist_ok=True)
        for k, v in dict(type=kind, online=online, voltage_now=voltage, current_now=current).items():
            (p / k).write_text(v)
    def expect(value):
        global checks
        result = subprocess.run(['sh', '-c', command], capture_output=True, text=True, check=True)
        assert result.stdout == value, (result.stdout, value)
        checks += 1
    supply('battery', 'Battery')
    expect('')
    supply('usb', 'USB_PD')
    expect('1 0 8226000 1891000 1 0') # Wireless is absent.
    supply('wireless', 'Wireless', '0')
    supply('ucsi-source-psy', 'USB', voltage='20000000')
    expect('1 0 8226000 1891000 1 0')
    supply('usb', 'USB_PD', '0')
    expect('') # Do not use UCSI negotiation/source data.
    supply('wireless', 'Wireless', voltage='9000000', current='2700000')
    expect('0 1 9000000 2700000 0 1')
    supply('wireless', 'Wireless', '0')
    supply('input-port', 'USB_C', voltage='5000000', current='3000000')
    expect('1 0 5000000 3000000 1 0')
    for bad in ['-1000', '0', '15000001', 'text', '9999999999999999999999999']:
        supply('input-port', 'USB_C', current=bad)
        expect('')
    for bad in ['4000', '2999999', '30000001', 'bad', '']:
        supply('input-port', 'USB_C', voltage=bad)
        expect('')
    supply('input-port', 'USB_C', online='2')
    expect('')
    assert 'Build.MODEL' not in source
    checks += 1
print(f'PASS: {checks} production input-source checks')

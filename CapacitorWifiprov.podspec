Pod::Spec.new do |spec|
  spec.name = 'CapacitorWifiprov'
  spec.version = '0.1.0'
  spec.summary = 'Capacitor bridge for Espressif ESP-IDF network provisioning.'
  spec.license = { :type => 'MIT', :file => 'LICENSE' }
  spec.homepage = 'https://github.com/example/capacitor-wifiprov'
  spec.author = { 'capacitor-wifiprov contributors' => 'noreply@example.com' }
  spec.source = { :git => 'https://github.com/example/capacitor-wifiprov.git', :tag => spec.version.to_s }
  spec.source_files = 'ios/Sources/**/*.{swift,h,m,c,cc,mm,cpp}'
  spec.ios.deployment_target = '15.0'
  spec.swift_version = '5.9'
  spec.dependency 'Capacitor', '>= 8.0.0', '< 9.0.0'
  spec.dependency 'ESPProvision', '~> 3.1.0'
end

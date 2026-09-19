Set-ItemProperty "HKLM:\SYSTEM\CurrentControlSet\Control\Session Manager\Power" -Name HiberbootEnabled -Value 0
"HiberbootEnabled = " + (Get-ItemProperty "HKLM:\SYSTEM\CurrentControlSet\Control\Session Manager\Power").HiberbootEnabled |
    Out-File "$env:TEMP\wol_fix_result.txt" -Encoding utf8

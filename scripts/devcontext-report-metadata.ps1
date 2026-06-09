function Get-DevContextOptionalProperty {
    param(
        [object]$Object,
        [string]$Name
    )

    if ($null -eq $Object) {
        return $null
    }
    if ($Object.PSObject.Properties.Name -contains $Name) {
        return $Object.$Name
    }
    return $null
}

function Protect-DevContextReportText {
    param([object]$Value)

    if ($null -eq $Value) {
        return $null
    }

    $text = [string]$Value
    if ([string]::IsNullOrWhiteSpace($text)) {
        return $text
    }

    $text = $text -replace '(?i)(authorization\s*:\s*bearer\s+)[^\s,;]+', '$1***'
    $text = $text -replace '(?i)(api[-_ ]?key\s*[:=]\s*)[^\s,;}\]]+', '$1***'
    $text = $text -replace '(?i)(AIza[0-9A-Za-z_-]{16,})', '***'
    $text = $text -replace '(?i)(sk-[0-9A-Za-z_-]{16,})', '***'
    return $text
}

function New-DevContextLlmReportMetadata {
    param(
        [object]$Data = $null,
        [string]$MetadataError = ""
    )

    $keyConfigured = Get-DevContextOptionalProperty -Object $Data -Name "keyConfigured"
    if ($null -ne $keyConfigured) {
        $keyConfigured = [bool]$keyConfigured
    }

    return [pscustomobject]@{
        provider = Protect-DevContextReportText (Get-DevContextOptionalProperty -Object $Data -Name "provider")
        model = Protect-DevContextReportText (Get-DevContextOptionalProperty -Object $Data -Name "model")
        status = Protect-DevContextReportText (Get-DevContextOptionalProperty -Object $Data -Name "status")
        keyStatus = Protect-DevContextReportText (Get-DevContextOptionalProperty -Object $Data -Name "keyStatus")
        keyConfigured = $keyConfigured
        lastCallStatus = Protect-DevContextReportText (Get-DevContextOptionalProperty -Object $Data -Name "lastCallStatus")
        lastErrorType = Protect-DevContextReportText (Get-DevContextOptionalProperty -Object $Data -Name "lastErrorType")
        lastErrorMessage = Protect-DevContextReportText (Get-DevContextOptionalProperty -Object $Data -Name "lastErrorMessage")
        lastCallAt = Protect-DevContextReportText (Get-DevContextOptionalProperty -Object $Data -Name "lastCallAt")
        metadataError = Protect-DevContextReportText $MetadataError
    }
}

function Format-DevContextReportValue {
    param(
        [object]$Value,
        [string]$Default = ""
    )

    $safeValue = Protect-DevContextReportText $Value
    if ($null -eq $safeValue -or [string]::IsNullOrWhiteSpace([string]$safeValue)) {
        return $Default
    }
    return [string]$safeValue
}

function Add-DevContextLlmReportMarkdownLines {
    param(
        [object[]]$Lines,
        [object]$LlmMetadata
    )

    if ($null -eq $LlmMetadata) {
        $LlmMetadata = New-DevContextLlmReportMetadata
    }

    $Lines += "- LLM provider: ``$(Format-DevContextReportValue -Value $LlmMetadata.provider -Default 'unknown')``"
    $Lines += "- LLM model: ``$(Format-DevContextReportValue -Value $LlmMetadata.model -Default 'unknown')``"
    $Lines += "- LLM status: ``$(Format-DevContextReportValue -Value $LlmMetadata.status -Default 'unknown')``"
    $Lines += "- LLM key configured: ``$(Format-DevContextReportValue -Value $LlmMetadata.keyConfigured -Default 'unknown')``"
    $Lines += "- LLM key status: ``$(Format-DevContextReportValue -Value $LlmMetadata.keyStatus -Default 'unknown')``"
    $Lines += "- LLM last call status: ``$(Format-DevContextReportValue -Value $LlmMetadata.lastCallStatus -Default 'none')``"
    $Lines += "- LLM last error type: ``$(Format-DevContextReportValue -Value $LlmMetadata.lastErrorType -Default 'none')``"
    $lastErrorMessage = Format-DevContextReportValue -Value $LlmMetadata.lastErrorMessage -Default ""
    if (-not [string]::IsNullOrWhiteSpace($lastErrorMessage)) {
        $Lines += "- LLM last error message: ``$lastErrorMessage``"
    }
    $lastCallAt = Format-DevContextReportValue -Value $LlmMetadata.lastCallAt -Default ""
    if (-not [string]::IsNullOrWhiteSpace($lastCallAt)) {
        $Lines += "- LLM last call at: ``$lastCallAt``"
    }
    $metadataError = Format-DevContextReportValue -Value $LlmMetadata.metadataError -Default ""
    if (-not [string]::IsNullOrWhiteSpace($metadataError)) {
        $Lines += "- LLM metadata fetch error: ``$metadataError``"
    }

    return @($Lines)
}

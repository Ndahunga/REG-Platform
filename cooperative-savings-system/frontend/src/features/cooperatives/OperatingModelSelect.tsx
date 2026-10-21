import {
  FormControl,
  FormControlLabel,
  FormHelperText,
  FormLabel,
  Radio,
  RadioGroup,
  Stack,
  Typography,
} from '@mui/material'
import { useTranslation } from 'react-i18next'
import type { SchemeOperatingModel } from '@/shared/types/cooperative'
import { OPERATING_MODELS } from './operatingModel'

interface OperatingModelSelectProps {
  /** Empty string means nothing is selected; there is deliberately no default model. */
  value: SchemeOperatingModel | ''
  onChange: (value: SchemeOperatingModel) => void
  error?: string
  disabled?: boolean
  name?: string
}

/** Required single choice of the Saving Scheme operating model, with a plain-language description of each. */
export function OperatingModelSelect({
  value,
  onChange,
  error,
  disabled,
  name = 'operatingModel',
}: OperatingModelSelectProps) {
  const { t } = useTranslation()
  const labelId = `${name}-label`
  return (
    <FormControl component="fieldset" required error={Boolean(error)} disabled={disabled} fullWidth>
      <FormLabel id={labelId} component="legend">
        {t('operatingModel.label')}
      </FormLabel>
      <RadioGroup
        aria-labelledby={labelId}
        name={name}
        value={value}
        onChange={(event) => onChange(event.target.value as SchemeOperatingModel)}
      >
        {OPERATING_MODELS.map((model) => (
          <FormControlLabel
            key={model}
            value={model}
            control={<Radio />}
            sx={{ alignItems: 'flex-start', mb: 1 }}
            label={
              <Stack>
                <Typography variant="body1" sx={{ fontWeight: 600 }}>
                  {t(`operatingModel.options.${model}.title`)}
                </Typography>
                <Typography variant="body2" color="text.secondary">
                  {t(`operatingModel.options.${model}.description`)}
                </Typography>
              </Stack>
            }
          />
        ))}
      </RadioGroup>
      {error ? <FormHelperText>{error}</FormHelperText> : null}
    </FormControl>
  )
}

import { ChangeDetectionStrategy, Component, Input, OnInit } from '@angular/core';
import { ControlContainer, FormGroupName } from '@angular/forms';
import { ArgumentType, Value } from '../../../../client';
import * as utils from '../../../../shared/utils';
import { TemplateProvider } from '../../CommandForm';

/**
 * Returns the stringified initial form value for a Value object.
 */
export function renderValue(value: Value): any {
  switch (value.type) {
    case 'BOOLEAN':
      return '' + value.booleanValue;
    case 'FLOAT':
      return '' + value.floatValue;
    case 'DOUBLE':
      return '' + value.doubleValue;
    case 'UINT32':
      return '' + value.uint32Value;
    case 'SINT32':
      return '' + value.sint32Value;
    case 'BINARY':
      return utils.convertBase64ToHex(value.binaryValue!);
    case 'ENUMERATED':
    case 'STRING':
    case 'TIMESTAMP':
      return value.stringValue!;
    case 'UINT64':
      return '' + value.uint64Value;
    case 'SINT64':
      return '' + value.sint64Value;
    case 'AGGREGATE':
      const { name: names, value: values } = value.aggregateValue!;
      const result: { [key: string]: any; } = {};
      for (let i = 0; i < names.length; i++) {
        result[names[i]] = renderValue(values[i]);
      }
      return result;
    case 'ARRAY':
      return value.arrayValue!.map(v => renderValue(v));
  }
}

/**
 * Returns the stringified initial form value for a JSON object.
 */
function renderJsonElement(jsonElement: any): any {
  if (Array.isArray(jsonElement)) {
    return jsonElement.map(el => renderJsonElement(el));
  } else if (typeof jsonElement === 'object') {
    const result: { [key: string]: any; } = {};
    for (const key in jsonElement) {
      result[key] = renderJsonElement(jsonElement[key]);
    }
    return result;
  } else {
    return '' + jsonElement;
  }
}

@Component({
  selector: 'app-argument',
  templateUrl: './ArgumentComponent.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
  viewProviders: [{
    provide: ControlContainer,
    useExisting: FormGroupName,
  }],
})
export class ArgumentComponent implements OnInit {

  @Input()
  name: string;

  @Input()
  type: ArgumentType;

  @Input()
  initialValue?: string;

  @Input()
  templateProvider: TemplateProvider;

  parsedInitialValue?: any;

  ngOnInit() {
    if (this.initialValue) {
      if (this.type.engType === 'AGGREGATE' || this.type.engType === 'ARRAY') {
        this.parsedInitialValue = renderJsonElement(JSON.parse(this.initialValue));
      } else if (this.type.engType === 'BOOLEAN') {
        this.parsedInitialValue = '' + (this.initialValue === this.type.oneStringValue);
      } else {
        this.parsedInitialValue = this.initialValue;
      }
    }

    if (this.templateProvider) {
      const previousValue = this.templateProvider.getAssignment(this.name);
      if (previousValue?.type === 'AGGREGATE') {
        this.parsedInitialValue = {
          ...this.parsedInitialValue || {},
          ...renderValue(previousValue),
        };
      } else if (previousValue?.type === 'ARRAY') {
        this.parsedInitialValue = renderValue(previousValue);
      }
    }
  }
}
